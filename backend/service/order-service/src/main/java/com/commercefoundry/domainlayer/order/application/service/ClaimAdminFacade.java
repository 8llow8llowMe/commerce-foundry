package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.ClaimAdminPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminActionProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 클레임 조회 · 결제 후 취소 수습 (#17). 수습은 PG 환불 · 재고 복원(원격 호출)이 섞여 <b>트랜잭션을 걸지 않는다</b> — DB 구간은
 * {@link ClaimAdminActionProcessor} 의 짧은 트랜잭션, 원격 구간은 고객 취소 · 재시도 스케줄러와 같은 {@link OrderCancellationPipelineProcessor} 다.
 * 원격이 성공한 뒤의 롤백으로 "PG 는 취소했는데 DB 는 모르는" 창을 만들지 않으려고 둘을 나눈다.
 *
 * <p>버전에서 지면(스케줄러 · 고객 · 다른 운영자가 먼저 바꿈) 다시 읽어 원하는 결과가 이미 났으면 바꾸지 않은 200, 아니면 {@code ORDER_025} 다
 * ({@code OrderAdminFacade} 와 같은 규칙). 수습은 성공 · 실패 모두 {@code admin.audit} 로 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class ClaimAdminFacade implements ClaimAdminUseCase {
    private final ClaimAdminProcessor processor;
    private final ClaimAdminActionProcessor actionProcessor;
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
        ClaimAdminResponse response = detail(claimId);
        audit.viewed(adminId, claimId);
        return response;
    }

    /**
     * 다시 열기 · 선점(tx) → 환불 · 복원(원격) → 결과 반영(tx). 선점에서 지면 늘 {@code ORDER_025} 다(다른 쪽이 지금 돌리고 있다).
     * 원격 단계의 실패는 클레임에 남아 스케줄러가 이으므로 응답은 그 시점의 상세다.
     */
    @Override
    public ClaimAdminResponse retry(long claimId, long adminId) {
        try {
            ClaimAdminActionProcessor.Prepared prepared = lockedOrConflict(claimId, () -> actionProcessor.prepareRetry(claimId), claim -> false, null);
            if (prepared.changed()) {
                progress(prepared.claim());
            }
        } catch (RuntimeException exception) {
            audit.retried(adminId, claimId, false);
            throw exception;
        }
        audit.retried(adminId, claimId, true);
        return detail(claimId);
    }

    @Override
    public ClaimAdminResponse reject(long claimId, String reason, long adminId) {
        try {
            lockedOrConflict(claimId, () -> actionProcessor.reject(claimId, reason, adminId), claim -> claim.status() == Claim.Status.REJECTED, false);
        } catch (RuntimeException exception) {
            audit.rejected(adminId, claimId, false);
            throw exception;
        }
        audit.rejected(adminId, claimId, true);
        return detail(claimId);
    }

    /** 확정(tx: 클레임 REFUNDED + 주문 취소 완료 + 이력) → 재고 복원(원격) → 결과 반영(tx). 복원 실패는 클레임에 남아 스케줄러 · 재시도가 잇는다. */
    @Override
    public ClaimAdminResponse confirmRefund(long claimId, String reason, long adminId) {
        try {
            Optional<Claim> confirmed = lockedOrConflict(claimId, () -> actionProcessor.confirmRefund(claimId, reason, adminId),
                claim -> ClaimAdminActionProcessor.isRefundedOrLater(claim.status()), Optional.empty());
            confirmed.ifPresent(this::progress);
        } catch (RuntimeException exception) {
            audit.refundConfirmed(adminId, claimId, false);
            throw exception;
        }
        audit.refundConfirmed(adminId, claimId, true);
        return detail(claimId);
    }

    @Override
    public ClaimAdminResponse complete(long claimId, String reason, long adminId) {
        try {
            lockedOrConflict(claimId, () -> actionProcessor.complete(claimId, reason, adminId), claim -> claim.status() == Claim.Status.COMPLETED, false);
        } catch (RuntimeException exception) {
            audit.completed(adminId, claimId, false);
            throw exception;
        }
        audit.completed(adminId, claimId, true);
        return detail(claimId);
    }

    private ClaimAdminResponse detail(long claimId) {
        return presenter.toResponse(processor.getDetail(claimId));
    }

    /**
     * DB 단계를 실행한다. 버전에서 지면 다시 읽어 {@code alreadyDone} 이면 {@code unchanged}(바꾸지 않음)를 돌려주고, 아니면 {@code ORDER_025} 다.
     */
    private <T> T lockedOrConflict(long claimId, Supplier<T> step, Predicate<Claim> alreadyDone, T unchanged) {
        try {
            return step.get();
        } catch (OptimisticLockingFailureException exception) {
            Claim current = actionProcessor.get(claimId);
            if (alreadyDone.test(current)) {
                return unchanged;
            }
            log.warn("admin claim action lost to a concurrent change. claimId={}, status={}", claimId, current.status());
            throw new OrderException(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);
        }
    }

    /** 원격 단계 — 고객 취소 · 스케줄러와 같은 경로. 반영하지 못한 실패는 미확정 한 번으로 세고(스케줄러와 같다) 응답은 그 시점의 상세로 낸다. */
    private void progress(Claim claim) {
        try {
            pipelineProcessor.progress(claim);
        } catch (RuntimeException exception) {
            log.error("admin claim action interrupted after commit, scheduler will continue. claimId={}, error={}", claim.id(),
                exception.getClass().getSimpleName(), RedactedThrowable.of(exception));
            try {
                orderCancellationProcessor.recordAttemptFailure(claim.orderId());
            } catch (RuntimeException countFailure) {
                log.error("admin claim attempt failure not counted. claimId={}, error={}", claim.id(), countFailure.getClass().getSimpleName(),
                    RedactedThrowable.of(countFailure));
            }
        }
    }
}
