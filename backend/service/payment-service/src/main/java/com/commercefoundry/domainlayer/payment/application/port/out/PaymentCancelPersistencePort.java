package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import java.util.List;
import java.util.Optional;

public interface PaymentCancelPersistencePort {
    Optional<Payment> lockPayment(long paymentId);
    Optional<PaymentCancel> findByClaimId(long claimId);
    /** Auto intent owns this positive ID; the order claim path must never reuse it. */
    boolean isAutoClaim(long claimId);
    List<PaymentCancel> findByPaymentId(long paymentId);
    /** Strict INSERT and flush; never reads or updates an existing claim. */
    PaymentCancel insertNewAutoClaim(PaymentCancel cancel);
    PaymentCancel save(PaymentCancel cancel);
}
