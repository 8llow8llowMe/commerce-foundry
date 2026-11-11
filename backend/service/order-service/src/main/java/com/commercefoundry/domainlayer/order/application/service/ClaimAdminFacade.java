package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.ClaimAdminPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminRecoveryProcessor;
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
 * 어드민 클레임 조회 · 결제 후 취소 다시 시도 · 수동 환불 확정 · 복원 생략 종결 (#17). 다시 시도 · 수동 확정은 PG 환불 · 재고 복원(원격 호출)이 이어져
 * <b>트랜잭션을 걸지 않는다</b> — 선점 · 확정은 {@link ClaimAdminRecoveryProcessor} 의 짧은 트랜잭션, 원격 구간은 고객 취소 · 재시도 스케줄러와 같은
 * {@link OrderCancellationPipelineProcessor} 다.
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
     * 수동 환불 확정(tx: 클레임 REFUNDED + 주문 취소 완료 + ADMIN 이력) → 재고 복원(원격) → 결과 반영(tx) (#17 A3). PG 는 부르지 않는다.
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
