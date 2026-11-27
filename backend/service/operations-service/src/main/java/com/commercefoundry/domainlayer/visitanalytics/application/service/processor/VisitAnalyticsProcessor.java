package com.commercefoundry.domainlayer.visitanalytics.application.service.processor;

import com.commercefoundry.domainlayer.visitanalytics.application.exception.VisitAnalyticsErrorCode;
import com.commercefoundry.domainlayer.visitanalytics.application.exception.VisitAnalyticsException;
import com.commercefoundry.domainlayer.visitanalytics.application.port.out.VisitAnalyticsStore;
import com.commercefoundry.domainlayer.visitanalytics.application.info.VisitAnalyticsInfos.Day;
import com.commercefoundry.domainlayer.visitanalytics.application.info.VisitAnalyticsInfos.Product;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VisitAnalyticsProcessor {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");
    private static final int RECEIPT_RETENTION_DAYS = 7;
    private static final int AGGREGATE_RETENTION_DAYS = 400;
    private static final int MAX_REPORT_DAYS = 31;

    private final VisitAnalyticsStore store;
    private final Clock clock;

    public void recordProductView(UUID eventId, long productId) {
        store.recordProductView(eventId, productId, today());
    }

    public List<Product> topProducts(LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        return store.topProducts(from, to).stream().map(row -> new Product(row.productId(), row.views())).toList();
    }

    public List<Day> dailyProduct(long productId, LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        return store.dailyProduct(productId, from, to).stream().map(row -> new Day(row.day(), row.views())).toList();
    }

    public void purgeExpiredData() {
        LocalDate today = today();
        store.purgeBefore(today.minusDays(RECEIPT_RETENTION_DAYS - 1), today.minusDays(AGGREGATE_RETENTION_DAYS - 1));
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        LocalDate today = today();
        if (from == null || to == null || from.isBefore(today.minusDays(AGGREGATE_RETENTION_DAYS - 1))
            || to.isAfter(today) || to.isBefore(from) || to.isAfter(from.plusDays(MAX_REPORT_DAYS - 1))) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_PERIOD);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(STORE_ZONE));
    }
}
