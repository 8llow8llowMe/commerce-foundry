package com.commercefoundry.domainlayer.payment.adapter.in.scheduler;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelSchedulerUseCase;
import com.commercefoundry.global.properties.PaymentAutoCancelSchedulerProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class PaymentAutoCancelScheduler {
    public static final String LOCK_NAME = "payment-auto-cancel";
    private final PaymentAutoCancelSchedulerUseCase useCase;
    private final LockProvider locks;
    private final PaymentAutoCancelSchedulerProperties properties;
    private final Clock clock;

    @Scheduled(initialDelayString = "${payment.auto-cancel.scheduler.initial-delay:PT1M}",
        fixedDelayString = "${payment.auto-cancel.scheduler.fixed-delay:PT1M}")
    public void sweep() {
        if (!properties.enabled()) return;
        try {
            var lock = locks.lock(new LockConfiguration(clock.instant(), LOCK_NAME, properties.lockAtMostFor(), Duration.ZERO));
            if (lock.isEmpty()) return;
            try { useCase.sweep(); }
            finally { lock.get().unlock(); }
        } catch (RuntimeException failure) {
            log.warn("Automatic cancellation scheduler: TICK_FAILED");
        }
    }
}
