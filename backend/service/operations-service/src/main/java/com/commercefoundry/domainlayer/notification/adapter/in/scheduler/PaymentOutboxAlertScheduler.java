package com.commercefoundry.domainlayer.notification.adapter.in.scheduler;

import com.commercefoundry.domainlayer.notification.application.port.in.PaymentOutboxAlertSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("operations.notifications.payment-outbox-alert.enabled")
public class PaymentOutboxAlertScheduler {
    private final PaymentOutboxAlertSchedulerUseCase useCase;
    @Scheduled(scheduler = "paymentOutboxAlertTaskScheduler",
        initialDelayString = "${operations.notifications.payment-outbox-alert.initial-delay:1m}",
        fixedDelayString = "${operations.notifications.payment-outbox-alert.fixed-delay:1m}")
    public void observe() {
        try {
            useCase.observe();
        } catch (RuntimeException ignored) {
            log.warn("Payment outbox observation unavailable; incident state preserved");
        }
    }
}
