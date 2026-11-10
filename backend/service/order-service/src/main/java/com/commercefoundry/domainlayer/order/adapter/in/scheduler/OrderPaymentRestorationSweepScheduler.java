package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationSweepUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 반영 재고 복원 스윕 (#582). 기본은 꺼져 있다({@code order.payment-restoration.sweep.enabled}) — 켜는 순서는 {@code deploy-guide.md} §4-4.
 * 인스턴스 간 중복 실행은 작업 행 선점(다음 시각 조건부 UPDATE)으로 막는다(ShedLock 없음, {@code ClaimCancellationRetryScheduler} 와 같다).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "order.payment-restoration.sweep.enabled", havingValue = "true")
public class OrderPaymentRestorationSweepScheduler {

    private final PaymentRestorationSweepUseCase useCase;

    @Scheduled(
        initialDelayString = "${order.payment-restoration.sweep.interval:1m}",
        fixedDelayString = "${order.payment-restoration.sweep.interval:1m}"
    )
    public void sweepDueRestorations() {
        useCase.sweepDueRestorations();
    }
}
