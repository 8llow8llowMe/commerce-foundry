package com.commercefoundry.domainlayer.sizechart.application.model;

import lombok.Getter;

/** Transport failures deliberately contain no remote body or parser diagnostics. */
@Getter
public class SizeChartDraftTransportException extends RuntimeException {
    private final SizeChartDraftTransportErrorCode errorCode;

    public SizeChartDraftTransportException(SizeChartDraftTransportErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SizeChartDraftTransportException(SizeChartDraftTransportErrorCode errorCode, Object... args) {
        this(errorCode);
    }

    public SizeChartDraftTransportException(SizeChartDraftTransportErrorCode errorCode, Throwable cause) {
        // Remote exceptions may contain raw input, headers or result JSON; never retain them.
        this(errorCode);
    }
}
