package com.commercefoundry.domainlayer.shippingfee.application.exception;

import lombok.Getter;

@Getter
public class DeliveryException extends RuntimeException {

    private final DeliveryErrorCode errorCode;

    public DeliveryException(DeliveryErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public DeliveryException(DeliveryErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public DeliveryException(DeliveryErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
