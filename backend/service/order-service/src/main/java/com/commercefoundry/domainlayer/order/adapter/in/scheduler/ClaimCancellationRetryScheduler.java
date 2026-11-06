package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import com.commercefoundry.domainlayer.order.application.port.in.ClaimCancellationRetrySchedulerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 후 취소 클레임의 미확정 환불 · 재고 복원 재시도 (#16 PR-2). 기본은 꺼져 있다 — 결제 개방(#15)과 함께 켠다({@code order.cancellation.retry.enabled}).
 * 인스턴스 간 중복 실행은 클레임 {@code @Version} 선점으로 막는다(ShedLock 없음).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "order.cancellation.retry.enabled", havingValue = "true")
public class ClaimCancellationRetryScheduler {

    private final ClaimCancellationRetrySchedulerUseCase useCase;

    @Scheduled(
        initialDelayString = "${order.cancellation.retry.interval:1m}",
        fixedDelayString = "${order.cancellation.retry.interval:1m}"
    )
    public void retryDueCancellations() {
        useCase.retryDueCancellations();
    }
}
