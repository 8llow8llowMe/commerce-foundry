package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimCancellationRetrySchedulerUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 클레임 재시도 (#16 PR-2). 인스턴스 간 중복 실행은 ShedLock 없이 클레임 {@code @Version} + 다음 시각으로 막는다 — 행마다 원격 호출 전에
 * 다음 시각을 {@code now + lease} 로 미루는 선점을 먼저 커밋하고, 이긴 행만 환불 · 복원을 다시 보낸다.
 *
 * <p>트랜잭션을 걸지 않는다 — 환불 · 복원은 원격 호출이라 DB 구간(선점 · 결과 반영 · 실패 기록)만 Processor 의 짧은 트랜잭션으로 나눈다. 한 행의 실패는
 * 그 행만 미확정 한 번으로 세고(상한을 넘으면 종결) 배치는 이어 간다. 한 번 실행은 lease 의 절반을 넘기면 남은 행을 다음 실행으로 넘긴다 —
 * 선점하지 않은 행은 다음 시각이 그대로라 다음 실행이 다시 집는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimCancellationRetrySchedulerFacade implements ClaimCancellationRetrySchedulerUseCase {

    private final OrderCancellationProcessor orderCancellationProcessor;
    private final OrderCancellationPipelineProcessor orderCancellationPipelineProcessor;
    private final OrderCancellationProperties properties;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void retryDueCancellations() {
        Instant deadline = clock.instant().plus(properties.retry().lease().dividedBy(2));
        List<Claim> targets = orderCancellationProcessor.findRetryTargets();
        int progressed = 0;
        for (int index = 0; index < targets.size(); index++) {
            if (clock.instant().isAfter(deadline)) {
                log.warn("cancellation retry run reached its time budget, deferring rest. remaining={}", targets.size() - index);
                break;
            }
            progressed += retryOne(targets.get(index)) ? 1 : 0;
        }
        if (progressed > 0) {
            log.info("cancellation claims retried. count={}", progressed);
        }
    }

    /** 선점에서 지면 건너뛴다. 로그는 원문 메시지 없이 타입 · 스택만 남긴다. */
    private boolean retryOne(Claim target) {
        Claim held;
        try {
            held = orderCancellationProcessor.hold(target);
        } catch (OptimisticLockingFailureException exception) {
            log.info("cancellation claim held by another worker, skipping. claimId={}", target.id());
            return false;
        } catch (RuntimeException exception) {
            log.error("cancellation claim hold failed. claimId={}, error={}", target.id(), exception.getClass().getSimpleName(), RedactedThrowable.of(exception));
            return false;
        }
        try {
            orderCancellationPipelineProcessor.progress(held);
            return true;
        } catch (RuntimeException exception) {
            log.error("cancellation claim retry failed. claimId={}, error={}, rootCause={}", target.id(), exception.getClass().getSimpleName(),
                NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName(), RedactedThrowable.of(exception));
            if (!(exception instanceof OrderException failure
                && failure.getErrorCode() == OrderErrorCode.CANCELLATION_REFUND_RECORDING_UNAVAILABLE)) {
                countFailure(held);
            }
            return false;
        }
    }

    /** 반영하지 못한 시도를 미확정 한 번으로 센다 — 이것마저 실패하면 다음 시각(lease)에 다시 집힌다. */
    private void countFailure(Claim held) {
        try {
            orderCancellationProcessor.recordAttemptFailure(held);
        } catch (RuntimeException exception) {
            log.error("cancellation claim failure count not saved. claimId={}, error={}", held.id(), exception.getClass().getSimpleName(),
                RedactedThrowable.of(exception));
        }
    }
}
