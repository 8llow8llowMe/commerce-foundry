package com.commercefoundry.domainlayer.visitanalytics.application.exception;

import lombok.Getter;

@Getter
public class VisitAnalyticsException extends RuntimeException {

    private final VisitAnalyticsErrorCode errorCode;

    public VisitAnalyticsException(VisitAnalyticsErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
