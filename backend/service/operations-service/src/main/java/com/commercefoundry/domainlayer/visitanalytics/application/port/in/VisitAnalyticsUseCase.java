package com.commercefoundry.domainlayer.visitanalytics.application.port.in;

import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Day;
import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Product;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface VisitAnalyticsUseCase {

    void recordProductView(UUID eventId, long productId);

    List<Product> topProducts(LocalDate from, LocalDate to);

    List<Day> dailyProduct(long productId, LocalDate from, LocalDate to);

    void purgeExpiredData();
}
