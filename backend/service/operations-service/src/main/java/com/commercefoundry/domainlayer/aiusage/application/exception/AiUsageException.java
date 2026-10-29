package com.commercefoundry.domainlayer.aiusage.application.exception;

import lombok.Getter;

@Getter
public class AiUsageException extends RuntimeException {

    private final AiUsageErrorCode errorCode;

    public AiUsageException(AiUsageErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public AiUsageException(AiUsageErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public AiUsageException(AiUsageErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
