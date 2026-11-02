package com.commercefoundry.domainlayer.payment.adapter.in.scheduler;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentRecoveryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {
    private final PaymentRecoveryUseCase facade;

    @Scheduled(initialDelayString = "${payment.recovery.initial-delay:PT1M}", fixedDelayString = "${payment.recovery.fixed-delay:PT1M}")
    public void recover() {
        facade.recover();
    }
}
