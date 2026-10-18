package com.commercefoundry.domainlayer.cart.application.exception;

import lombok.Getter;

@Getter
public class CartException extends RuntimeException {

    private final CartErrorCode errorCode;

    public CartException(CartErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public CartException(CartErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public CartException(CartErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
