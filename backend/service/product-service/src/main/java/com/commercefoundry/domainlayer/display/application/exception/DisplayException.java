package com.commercefoundry.domainlayer.display.application.exception;

import lombok.Getter;

@Getter
public class DisplayException extends RuntimeException {

    private final DisplayErrorCode errorCode;

    public DisplayException(DisplayErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public DisplayException(DisplayErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public DisplayException(DisplayErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
