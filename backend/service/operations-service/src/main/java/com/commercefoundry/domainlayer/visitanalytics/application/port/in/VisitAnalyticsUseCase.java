package com.commercefoundry.domainlayer.visitanalytics.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface VisitAnalyticsUseCase {

    void recordProductView(UUID eventId, long productId);

    List<ProductViews> topProducts(LocalDate from, LocalDate to);

    List<DailyViews> dailyProduct(long productId, LocalDate from, LocalDate to);

    record ProductViews(long productId, long views) {
    }

    record DailyViews(LocalDate day, long views) {
    }
}
