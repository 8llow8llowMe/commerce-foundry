package com.commercefoundry.domainlayer.payment.adapter.in.scheduler;

import com.commercefoundry.domainlayer.payment.application.port.in.OrderReconciliationSchedulerUseCase;
import com.commercefoundry.global.properties.PaymentOrderReconciliationProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class OrderReconciliationScheduler {
    public static final String LOCK_NAME = "payment-order-reconciliation";
    private final OrderReconciliationSchedulerUseCase useCase;
    private final LockProvider locks;
    private final PaymentOrderReconciliationProperties properties;
    private final Clock clock;

    @Scheduled(initialDelayString = "${payment.order-result.reconciliation.initial-delay:PT1M}",
        fixedDelayString = "${payment.order-result.reconciliation.fixed-delay:PT1M}")
    public void reconcile() {
        if (!properties.enabled()) return;
        try {
            var acquired = locks.lock(new LockConfiguration(clock.instant(), LOCK_NAME, properties.lockAtMostFor(), Duration.ZERO));
            if (acquired.isEmpty()) return;
            try { useCase.reconcile(); }
            finally { acquired.get().unlock(); }
        } catch (RuntimeException failure) {
            log.warn("Order reconciliation scheduler: TICK_FAILED");
        }
    }
}
