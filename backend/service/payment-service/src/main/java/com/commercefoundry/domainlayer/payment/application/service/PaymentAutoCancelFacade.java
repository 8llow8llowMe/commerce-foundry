package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelExecutionProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** External work remains outside the short payment and cancellation transactions. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class PaymentAutoCancelFacade implements PaymentAutoCancelUseCase {
    private final PaymentAutoCancelExecutionProcessor processor;

    @Override
    public void execute(long paymentId) {
        processor.execute(paymentId);
    }
}
