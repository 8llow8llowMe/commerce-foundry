package com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase.DailyViews;
import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase.ProductViews;
import java.time.LocalDate;
import java.util.List;

public final class VisitAnalyticsPresenter {

    private VisitAnalyticsPresenter() {
    }

    public static List<Product> products(List<ProductViews> rows) {
        return rows.stream().map(row -> new Product(Long.toString(row.productId()), row.views())).toList();
    }

    public static List<Day> days(List<DailyViews> rows) {
        return rows.stream().map(row -> new Day(row.day(), row.views())).toList();
    }

    public record Product(String productId, long views) {
    }

    public record Day(LocalDate day, long views) {
    }
}
