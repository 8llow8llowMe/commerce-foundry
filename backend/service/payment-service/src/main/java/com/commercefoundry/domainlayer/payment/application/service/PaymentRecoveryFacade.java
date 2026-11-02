package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentRecoveryUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentRecoveryScanProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 GET 전후의 건별 커밋을 조합한다. 호출자 트랜잭션도 NEVER 경계로 거절한다. */
@Component
@RequiredArgsConstructor
public class PaymentRecoveryFacade implements PaymentRecoveryUseCase {
    private final PaymentRecoveryScanProcessor processor;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void recover() {
        processor.recover();
    }
}
