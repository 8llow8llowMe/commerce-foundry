package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import java.util.List;
import java.util.Optional;

public interface PaymentCancelPersistencePort {
    Optional<Payment> lockPayment(long paymentId);
    Optional<PaymentCancel> findByClaimId(long claimId);
    List<PaymentCancel> findByPaymentId(long paymentId);
    PaymentCancel save(PaymentCancel cancel);
}
