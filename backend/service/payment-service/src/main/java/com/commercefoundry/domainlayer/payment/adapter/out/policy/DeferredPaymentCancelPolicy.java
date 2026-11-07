package com.commercefoundry.domainlayer.payment.adapter.out.policy;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fast hook: persists an intent only. No PG call and no in-memory execution queue. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class DeferredPaymentCancelPolicy implements PaymentCancelPolicy {
    private final PaymentAutoCancelProcessor processor;
    @Value("${payment.auto-cancel.enabled:false}")
    private boolean enabled;

    @Override
    public void onRejected(long paymentId) {
        if (enabled) {
            processor.enqueue(paymentId);
        }
    }
}
