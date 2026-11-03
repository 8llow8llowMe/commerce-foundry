package com.commercefoundry.domainlayer.payment.adapter.in.scheduler;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebhookSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentWebhookScheduler {
    private final PaymentWebhookSchedulerUseCase useCase;
    @Scheduled(scheduler = "paymentWebhookTaskScheduler", initialDelayString = "${payment.webhook.initial-delay:PT1M}",
        fixedDelayString = "${payment.webhook.fixed-delay:PT5S}")
    public void process() { useCase.process(); }
}
