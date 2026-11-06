package com.commercefoundry.domainlayer.notification.adapter.in.scheduler;

import com.commercefoundry.domainlayer.notification.application.port.in.PaymentAnomalyAlertSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("operations.notifications.payment-anomaly-alert.enabled")
public class PaymentAnomalyAlertScheduler {
    private final PaymentAnomalyAlertSchedulerUseCase useCase;

    @Scheduled(scheduler = "paymentAnomalyAlertTaskScheduler",
        initialDelayString = "${operations.notifications.payment-anomaly-alert.initial-delay:1m}",
        fixedDelayString = "${operations.notifications.payment-anomaly-alert.fixed-delay:1m}")
    public void observe() { useCase.observe(); }
}
