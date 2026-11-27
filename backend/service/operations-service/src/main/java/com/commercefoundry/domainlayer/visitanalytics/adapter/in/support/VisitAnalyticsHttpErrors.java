package com.commercefoundry.domainlayer.visitanalytics.adapter.in.support;

import com.commercefoundry.domainlayer.visitanalytics.application.exception.VisitAnalyticsErrorCode;
import com.commercefoundry.domainlayer.visitanalytics.application.exception.VisitAnalyticsException;

public final class VisitAnalyticsHttpErrors {

    private VisitAnalyticsHttpErrors() {
    }

    public static RuntimeException clientSubjectRequired() {
        return new VisitAnalyticsException(VisitAnalyticsErrorCode.CLIENT_SUBJECT_REQUIRED);
    }

    public static RuntimeException bodyTooLarge() {
        return new VisitAnalyticsException(VisitAnalyticsErrorCode.BODY_TOO_LARGE);
    }

    public static RuntimeException unsupportedBody() {
        return new VisitAnalyticsException(VisitAnalyticsErrorCode.UNSUPPORTED_BODY);
    }

    public static RuntimeException invalidEvent() {
        return new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_EVENT);
    }

    public static RuntimeException invalidPeriod() {
        return new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_PERIOD);
    }

    public static RuntimeException invalidProduct() {
        return new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_PRODUCT);
    }
}
