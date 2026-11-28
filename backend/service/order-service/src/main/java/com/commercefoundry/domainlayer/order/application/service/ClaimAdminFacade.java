package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.ClaimAdminPresenter;
import com.commercefoundry.domainlayer.order.application.command.ClaimReturnApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminRecoveryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimCancellationRejectionProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimCancellationVoidProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimReturnDecisionProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 클레임 조회 · 결제 후 취소 다시 시도 · 수동 환불 확정 · 복원 생략 종결 · 반품 승인 · 거절 · 결제 후 취소 거절 (#17). 다시 시도 · 수동 확정 · 반품 승인은
 * PG 환불 · 재고 복원(원격 호출)이, 취소 거절은 payment 무환불 판정(원격 호출)이 이어져 <b>트랜잭션을 걸지 않는다</b> — 선점 · 확정 · 승인 · 거절은
 * {@link ClaimAdminRecoveryProcessor} · {@link ClaimReturnDecisionProcessor} · {@link ClaimCancellationRejectionProcessor} 의 짧은 트랜잭션, 원격 구간은 고객 취소 ·
 * 재시도 스케줄러와 같은 {@link OrderCancellationPipelineProcessor} 와 무환불 판정 {@link ClaimCancellationVoidProcessor} 다.
 * 원격이 성공한 뒤의 롤백으로 "PG 는 취소했는데 DB 는 모르는" 창을 만들지 않으려고 둘을 나눈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class ClaimAdminFacade implements ClaimAdminUseCase {
    private final ClaimAdminProcessor processor;
    private final ClaimAdminRecoveryProcessor recoveryProcessor;
    private final OrderCancellationProcessor orderCancellationProcessor;
    private final OrderCancellationPipelineProcessor pipelineProcessor;
    private final ClaimAdminPresenter presenter;
    private final ClaimAdminAuditProcessor audit;
    private final ClaimReturnDecisionProcessor returnDecisionProcessor;
    private final ClaimCancellationRejectionProcessor cancellationRejectionProcessor;
    private final ClaimCancellationVoidProcessor cancellationVoidProcessor;

    @Override
    public PageResponse<ClaimAdminItem> getClaims(ClaimAdminFilter filter, long adminId) {
        PageResponse<ClaimAdminItem> response = presenter.toPageResponse(processor.search(filter));
        audit.searched(adminId, filter);
        return response;
    }

    @Override
    public ClaimAdminResponse getClaim(long claimId, long adminId) {
        ClaimAdminResponse response = presenter.toResponse(processor.getDetail(claimId));
        audit.viewed(adminId, claimId);
        return response;
    }

    /**
     * 선점(tx) → 환불 · 복원(원격) → 결과 반영(tx). 원격 단계가 반영하지 못하고 끊기면 스케줄러와 같이 미확정 한 번으로 세고 그 시점의 상세를 낸다 —
     * 클레임은 대기 상태 + lease 로 남아 스케줄러가 잇는다. 확정 환불 미기록(503 {@code ORDER_036})만 그대로 전달한다.
     */
    @Override
    public ClaimAdminResponse retry(long claimId, long expectedVersion, long adminId) {
        try {
            Claim held = recoveryProcessor.prepareRetry(claimId, expectedVersion);
            progress(held);
        } catch (RuntimeException exception) {
            audit.retried(adminId, claimId, false);
            throw exception;
        }
        audit.retried(adminId, claimId, true);
        return presenter.toResponse(processor.getDetail(claimId));
    }

    /**
     * 수동 환불 확정(tx: 클레임 REFUNDED + 주문 정리 + ADMIN 이력) → 재고 복원(원격) → 결과 반영(tx) (#17 A3). PG 는 부르지 않는다.
     * 확정이 커밋되면 성공이다 — 복원이 끊기면 다시 시도와 같이 미확정 한 번으로 세고(클레임은 REFUNDED + lease) 스케줄러가 잇는다.
     * 버전 대조 · 저장 경합은 {@link ClaimAdminRecoveryProcessor} 가 409 로 바꾼다(저장은 flush 까지 하므로 커밋 때 새 충돌은 없다).
     */
    @Override
    public ClaimAdminResponse confirmRefund(long claimId, long expectedVersion, String reason, long adminId) {
        try {
            Claim refunded = recoveryProcessor.confirmRefund(claimId, expectedVersion, reason, adminId);
            progress(refunded);
        } catch (RuntimeException exception) {
            audit.refundConfirmed(adminId, claimId, false);
            throw exception;
        }
        audit.refundConfirmed(adminId, claimId, true);
        return presenter.toResponse(processor.getDetail(claimId));
    }

    /** 복원 생략 종결(tx) — 원격 호출 없음 (#17 A3). */
    @Override
    public ClaimAdminResponse complete(long claimId, long expectedVersion, String reason, long adminId) {
        try {
            recoveryProcessor.complete(claimId, expectedVersion, reason, adminId);
        } catch (RuntimeException exception) {
            audit.completed(adminId, claimId, false);
            throw exception;
        }
        audit.completed(adminId, claimId, true);
        return presenter.toResponse(processor.getDetail(claimId));
    }

    /**
     * 반품 승인(tx: 주문 잠금 · 판정 · 환불액 대조 · APPROVED) → 환불 · 재고 복원(원격) → 현재 상세 (#17 B2c-2). 승인이 커밋되면 성공 감사를 바로 남긴다 —
     * 이어지는 환불이 끊기면 다시 시도와 같이 미확정 한 번으로 세고(클레임은 APPROVED + lease) 스케줄러가 잇는다. 확정 환불 미기록(503 {@code ORDER_036})만 전달한다.
     */
    @Override
    public ClaimAdminResponse approveReturn(ClaimReturnApprovalCommand command, long adminId) {
        Claim approved;
        try {
            approved = returnDecisionProcessor.approve(command, adminId);
        } catch (RuntimeException exception) {
            audit.returnApproved(adminId, command.claimId(), false);
            throw exception;
        }
        audit.returnApproved(adminId, command.claimId(), true);
        progress(approved);
        return presenter.toResponse(processor.getDetail(command.claimId()));
    }

    /** 반품 거절(tx: 주문 잠금 · REJECTED · 주문 정리 · ADMIN 이력) — 원격 호출 없음 (#17 B2c-2). */
    @Override
    public ClaimAdminResponse rejectReturn(long claimId, long expectedVersion, String reason, long adminId) {
        try {
            returnDecisionProcessor.reject(claimId, expectedVersion, reason, adminId);
        } catch (RuntimeException exception) {
            audit.returnRejected(adminId, claimId, false);
            throw exception;
        }
        audit.returnRejected(adminId, claimId, true);
        return presenter.toResponse(processor.getDetail(claimId));
    }

    /**
     * 결제 후 취소 거절 (#17 A4) — 자격 확인(tx1, 읽기 전용) → payment 무환불 판정 1회(트랜잭션 밖) → 거절 기록(tx2, 주문 NOWAIT 잠금 · 재판정 · 저장) →
     * 현재 상세. 판정은 PG 잔액 조회를 기다리는 원격 호출이라 그동안 DB 커넥션 · 주문 잠금을 쥐지 않으려고 트랜잭션을 단계별 Processor 로 나눈다.
     * 자격을 통과한 뒤에만 판정을 부르고(결과 미확인 클레임이 봉인되면 다시 시도 경로가 막힌다), 무환불 확정이 아니면 tx2 를 부르지 않는다.
     * 재고 복원 · 환불 경로({@link OrderCancellationPipelineProcessor})는 부르지 않는다. 처리 횟수를 세지 않는다 — 거절은 자동 처리 대상이 아니다.
     */
    @Override
    public ClaimAdminResponse rejectCancellation(long claimId, long expectedVersion, String reason, long adminId) {
        try {
            Claim eligible = cancellationRejectionProcessor.prepare(claimId, expectedVersion);
            cancellationVoidProcessor.confirmNoRefund(eligible);
            recordRejection(eligible, expectedVersion, reason, adminId);
        } catch (RuntimeException exception) {
            audit.cancellationRejected(adminId, claimId, false);
            throw exception;
        }
        audit.cancellationRejected(adminId, claimId, true);
        return presenter.toResponse(processor.getDetail(claimId));
    }

    /**
     * payment 가 이미 무환불을 봉인한 뒤의 거절 기록(tx2). 여기서 지면 payment 는 봉인됐는데 order 는 거절을 남기지 못한 상태다 — 상세를 다시 읽어 다시 거절하면
     * payment 가 같은 판정(확정)을 다시 주므로 그대로 끝난다. 그 사이 다른 대표가 환불 완료로 확정했다면 결제 관리 화면과 대조한다(#693). 그래서 예외는 바꾸지 않고 그대로 내고, 어느 클레임인지만 warn 으로 남긴다.
     */
    private void recordRejection(Claim sealed, long expectedVersion, String reason, long adminId) {
        try {
            cancellationRejectionProcessor.reject(sealed.id(), expectedVersion, reason, adminId);
        } catch (RuntimeException exception) {
            log.warn("cancellation no refund sealed in payment but rejection not recorded. claimId={}, orderId={}, paymentId={}, error={}", sealed.id(),
                sealed.orderId(), sealed.paymentId(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private void progress(Claim held) {
        try {
            pipelineProcessor.progress(held);
        } catch (OrderException exception) {
            if (exception.getErrorCode() == OrderErrorCode.CANCELLATION_REFUND_RECORDING_UNAVAILABLE) {
                throw exception;
            }
            countFailure(held, exception);
        } catch (RuntimeException exception) {
            countFailure(held, exception);
        }
    }

    private void countFailure(Claim held, RuntimeException exception) {
        log.error("admin claim recovery interrupted after commit, scheduler will continue. claimId={}, error={}", held.id(), exception.getClass().getSimpleName(),
            RedactedThrowable.of(exception));
        try {
            orderCancellationProcessor.recordAttemptFailure(held);
        } catch (RuntimeException countFailure) {
            log.error("admin claim retry failure count not saved. claimId={}, error={}", held.id(), countFailure.getClass().getSimpleName(),
                RedactedThrowable.of(countFailure));
        }
    }
}
