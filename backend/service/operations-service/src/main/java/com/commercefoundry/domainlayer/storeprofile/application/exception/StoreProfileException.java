package com.commercefoundry.domainlayer.storeprofile.application.exception;

import lombok.Getter;

@Getter
public class StoreProfileException extends RuntimeException {

    private final StoreProfileErrorCode errorCode;

    public StoreProfileException(StoreProfileErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public StoreProfileException(StoreProfileErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public StoreProfileException(StoreProfileErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
