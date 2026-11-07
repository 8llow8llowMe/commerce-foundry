package com.commercefoundry.domainlayer.payment.application.exception;

/** Unsaved automatic claim collision; retry only after the failed transaction has rolled back. */
public class PaymentAutoCancelClaimCollisionException extends PaymentException {
    public PaymentAutoCancelClaimCollisionException() { super(PaymentErrorCode.CANCELLATION_KEY_REUSED); }
}
