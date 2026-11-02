package com.commercefoundry.domainlayer.shipment.application.exception;

import lombok.Getter;

@Getter
public class ShipmentException extends RuntimeException {

    private final ShipmentErrorCode errorCode;

    public ShipmentException(ShipmentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public ShipmentException(ShipmentErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public ShipmentException(ShipmentErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
