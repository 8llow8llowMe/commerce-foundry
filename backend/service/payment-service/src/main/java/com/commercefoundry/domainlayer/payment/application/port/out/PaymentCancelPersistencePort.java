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
    /**
     * VOIDED 봉인 행의 엄격 INSERT + flush (#691). 기존 행을 읽거나 고치지 않는다.
     *
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException 409 {@code PAYMENT_012} — 같은 claimId 행이 먼저 커밋됐다
     */
    PaymentCancel insertVoided(PaymentCancel cancel);
    PaymentCancel save(PaymentCancel cancel);
}
