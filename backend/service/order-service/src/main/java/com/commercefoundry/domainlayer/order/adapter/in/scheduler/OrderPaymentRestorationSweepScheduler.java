package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationSweepUseCase;
import com.commercefoundry.global.config.OrderPaymentRestorationLock;
import com.commercefoundry.global.properties.OrderPaymentRestorationSweepProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;

/** PAYMENT_RECORD 복원 배치 진입점. 스윕을 켠 컨텍스트에만 등록한다. */
@RequiredArgsConstructor
public class OrderPaymentRestorationSweepScheduler {

    private final OrderPaymentRestorationLock lock;
    private final PaymentRestorationSweepUseCase useCase;
    private final OrderPaymentRestorationSweepProperties settings;

    @Scheduled(
        initialDelayString = "${order.payment-restoration.sweep.interval:1m}",
        fixedDelayString = "${order.payment-restoration.sweep.interval:1m}"
    )
    public void sweepDuePaymentRestorations() {
        lock.runIfAcquired(settings.lockAtMostFor(), () -> useCase.sweep(settings.batchSize(), settings.initialBackoff(), settings.maxBackoff()));
    }
}
