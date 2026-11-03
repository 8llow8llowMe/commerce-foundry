package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;

@Getter
public class SkuAdminException extends RuntimeException {
    private final SkuAdminErrorCode errorCode;

    public SkuAdminException(SkuAdminErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SkuAdminException(SkuAdminErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public SkuAdminException(SkuAdminErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
