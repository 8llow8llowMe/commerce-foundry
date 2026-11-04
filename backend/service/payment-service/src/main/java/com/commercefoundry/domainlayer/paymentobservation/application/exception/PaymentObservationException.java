package com.commercefoundry.domainlayer.paymentobservation.application.exception;

import lombok.Getter;

@Getter
public class PaymentObservationException extends RuntimeException {

    private final PaymentObservationErrorCode errorCode;

    public PaymentObservationException(PaymentObservationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public PaymentObservationException(PaymentObservationErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public PaymentObservationException(PaymentObservationErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
