package com.commercefoundry.domainlayer.storenavigation.application.exception;

import lombok.Getter;

@Getter
public class StoreNavigationException extends RuntimeException {
    private final StoreNavigationErrorCode errorCode;

    public StoreNavigationException(StoreNavigationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public StoreNavigationException(StoreNavigationErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public StoreNavigationException(StoreNavigationErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
