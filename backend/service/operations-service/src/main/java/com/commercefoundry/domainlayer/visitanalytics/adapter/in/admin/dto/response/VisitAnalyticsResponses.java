package com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response;

import java.time.LocalDate;

public final class VisitAnalyticsResponses {

    private VisitAnalyticsResponses() {
    }

    public record Product(String productId, long views) {
    }

    public record Day(LocalDate day, long views) {
    }
}
