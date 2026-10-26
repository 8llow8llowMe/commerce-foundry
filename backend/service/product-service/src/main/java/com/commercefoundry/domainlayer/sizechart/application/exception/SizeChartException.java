package com.commercefoundry.domainlayer.sizechart.application.exception;

import lombok.Getter;

@Getter
public class SizeChartException extends RuntimeException {

    private final SizeChartErrorCode errorCode;

    public SizeChartException(SizeChartErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public SizeChartException(SizeChartErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public SizeChartException(SizeChartErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
