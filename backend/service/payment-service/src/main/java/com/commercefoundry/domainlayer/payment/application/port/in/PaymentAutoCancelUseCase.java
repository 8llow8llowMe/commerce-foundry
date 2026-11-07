package com.commercefoundry.domainlayer.payment.application.port.in;

/** Explicit execution after intent commit; candidate scanning is a separate concern. */
public interface PaymentAutoCancelUseCase {
    void execute(long paymentId);
}
