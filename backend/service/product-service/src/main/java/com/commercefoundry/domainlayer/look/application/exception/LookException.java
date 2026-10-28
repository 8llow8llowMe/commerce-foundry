package com.commercefoundry.domainlayer.look.application.exception;

import lombok.Getter;

@Getter
public class LookException extends RuntimeException {

    private final LookErrorCode errorCode;

    public LookException(LookErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public LookException(LookErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public LookException(LookErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
