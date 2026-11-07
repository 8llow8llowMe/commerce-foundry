package com.commercefoundry.domainlayer.storefooter.application.exception;

import lombok.Getter;

@Getter
public class StoreFooterException extends RuntimeException {
    private final StoreFooterErrorCode errorCode;

    public StoreFooterException(StoreFooterErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public StoreFooterException(StoreFooterErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public StoreFooterException(StoreFooterErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
