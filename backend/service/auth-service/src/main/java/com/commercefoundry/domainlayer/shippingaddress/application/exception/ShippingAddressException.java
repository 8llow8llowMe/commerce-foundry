package com.commercefoundry.domainlayer.shippingaddress.application.exception;

import lombok.Getter;

@Getter
public class ShippingAddressException extends RuntimeException {

    private final ShippingAddressErrorCode errorCode;

    public ShippingAddressException(ShippingAddressErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public ShippingAddressException(ShippingAddressErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public ShippingAddressException(ShippingAddressErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
