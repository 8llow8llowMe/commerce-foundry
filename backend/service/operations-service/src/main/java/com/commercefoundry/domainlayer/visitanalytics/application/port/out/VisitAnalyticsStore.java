package com.commercefoundry.domainlayer.visitanalytics.application.port.out;

import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase.DailyViews;
import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase.ProductViews;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface VisitAnalyticsStore {

    void recordProductView(UUID eventId, long productId, LocalDate day);

    List<ProductViews> topProducts(LocalDate from, LocalDate to);

    List<DailyViews> dailyProduct(long productId, LocalDate from, LocalDate to);

    void purgeBefore(LocalDate receiptCutoff, LocalDate aggregateCutoff);
}
