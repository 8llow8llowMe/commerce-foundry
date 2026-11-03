package com.commercefoundry.domainlayer.guestsession.application.exception;

import lombok.Getter;

@Getter
public class GuestSessionException extends RuntimeException {
    private final GuestSessionErrorCode errorCode;

    public GuestSessionException(GuestSessionErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public GuestSessionException(GuestSessionErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public GuestSessionException(GuestSessionErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
