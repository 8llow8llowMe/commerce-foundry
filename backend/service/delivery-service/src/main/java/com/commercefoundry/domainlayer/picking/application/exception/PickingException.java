package com.commercefoundry.domainlayer.picking.application.exception;

import lombok.Getter;

@Getter
public class PickingException extends RuntimeException {

    private final PickingErrorCode errorCode;

    public PickingException(PickingErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public PickingException(PickingErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public PickingException(PickingErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
