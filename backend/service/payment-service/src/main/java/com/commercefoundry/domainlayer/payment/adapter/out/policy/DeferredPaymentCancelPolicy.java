package com.commercefoundry.domainlayer.payment.adapter.out.policy;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 기본 정책은 실제 PG 취소를 실행하지 않는다. 영속 거절은 #591/#579에서 복구·실행한다. */
@Component
@Transactional(propagation = Propagation.NEVER)
public class DeferredPaymentCancelPolicy implements PaymentCancelPolicy {
    @Override
    public void onRejected(long paymentId) { }
}
