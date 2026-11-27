package com.commercefoundry.domainlayer.visitanalytics.application.service;

import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Day;
import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Product;
import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.presenter.VisitAnalyticsPresenter;
import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase;
import com.commercefoundry.domainlayer.visitanalytics.application.service.processor.VisitAnalyticsProcessor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VisitAnalyticsFacade implements VisitAnalyticsUseCase {

    private final VisitAnalyticsProcessor processor;

    @Override
    public void recordProductView(UUID eventId, long productId) {
        processor.recordProductView(eventId, productId);
    }

    @Override
    public List<Product> topProducts(LocalDate from, LocalDate to) {
        return processor.topProducts(from, to).stream().map(row -> VisitAnalyticsPresenter.product(row.productId(), row.views())).toList();
    }

    @Override
    public List<Day> dailyProduct(long productId, LocalDate from, LocalDate to) {
        return processor.dailyProduct(productId, from, to).stream().map(row -> VisitAnalyticsPresenter.day(row.day(), row.views())).toList();
    }

    @Override
    public void purgeExpiredData() {
        processor.purgeExpiredData();
    }
}
