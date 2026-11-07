package com.commercefoundry.domainlayer.sizechartgeneration.application.exception;

import lombok.Getter;

@Getter
public class SizeChartGenerationException extends RuntimeException {
    private final SizeChartGenerationErrorCode errorCode;

    public SizeChartGenerationException(SizeChartGenerationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SizeChartGenerationException(SizeChartGenerationErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public SizeChartGenerationException(SizeChartGenerationErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
