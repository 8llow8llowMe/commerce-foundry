package com.commercefoundry.domainlayer.job.application.exception;

import lombok.Getter;

@Getter
public class JobException extends RuntimeException {

    private final JobErrorCode errorCode;

    public JobException(JobErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public JobException(JobErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public JobException(JobErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
