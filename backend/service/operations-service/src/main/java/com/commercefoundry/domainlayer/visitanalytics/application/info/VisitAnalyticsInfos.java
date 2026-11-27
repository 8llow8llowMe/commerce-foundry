package com.commercefoundry.domainlayer.visitanalytics.application.info;

import java.time.LocalDate;

public final class VisitAnalyticsInfos {

    private VisitAnalyticsInfos() {
    }

    public record Product(long productId, long views) {
    }

    public record Day(LocalDate day, long views) {
    }
}
