package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;

@Getter
public class ProductException extends RuntimeException {

    private final ProductErrorCode errorCode;

    public ProductException(ProductErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public ProductException(ProductErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public ProductException(ProductErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
