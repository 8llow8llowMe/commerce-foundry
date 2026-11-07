package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import java.util.Optional;

/** Intent persistence. The caller must lock the payment row before reading or writing. */
public interface PaymentAutoCancelStore {
    Optional<PaymentAutoCancel> find(long paymentId);
    PaymentAutoCancel save(PaymentAutoCancel intent);
}
