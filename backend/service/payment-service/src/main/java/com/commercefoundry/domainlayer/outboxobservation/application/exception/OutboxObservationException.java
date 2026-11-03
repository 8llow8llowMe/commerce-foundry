package com.commercefoundry.domainlayer.outboxobservation.application.exception;

import lombok.Getter;

@Getter
public class OutboxObservationException extends RuntimeException {

    private final OutboxObservationErrorCode errorCode;

    public OutboxObservationException(OutboxObservationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public OutboxObservationException(OutboxObservationErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public OutboxObservationException(OutboxObservationErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
