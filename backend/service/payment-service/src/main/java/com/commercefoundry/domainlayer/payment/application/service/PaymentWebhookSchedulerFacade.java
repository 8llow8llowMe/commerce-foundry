package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebhookSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentWebhookScanProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentWebhookSchedulerFacade implements PaymentWebhookSchedulerUseCase {
    private final PaymentWebhookScanProcessor processor;

    /** PG GET은 트랜잭션 밖이다. 한 tick당 batchSize개까지만 건별 claim·조회·기록한다. */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void process() {
        processor.process();
    }
}
