package com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Day;
import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Product;
import java.time.LocalDate;

public final class VisitAnalyticsPresenter {

    private VisitAnalyticsPresenter() {
    }

    public static Product product(long productId, long views) {
        return new Product(Long.toString(productId), views);
    }

    public static Day day(LocalDate day, long views) {
        return new Day(day, views);
    }
}
