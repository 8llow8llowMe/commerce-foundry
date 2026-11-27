package com.commercefoundry.domainlayer.visitanalytics.adapter.out.persistence;

import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase.DailyViews;
import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase.ProductViews;
import com.commercefoundry.domainlayer.visitanalytics.application.port.out.VisitAnalyticsStore;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class VisitAnalyticsJdbcAdapter implements VisitAnalyticsStore {

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void recordProductView(UUID eventId, long productId, LocalDate day) {
        int claimed = jdbc.update("""
            insert into operations.visit_event_receipt (event_id, visit_day)
            values (?, ?) on conflict do nothing
            """, eventId, day);
        if (claimed == 0) {
            return;
        }

        jdbc.update("""
            insert into operations.visit_product_daily (visit_day, product_id, view_count)
            values (?, ?, 0) on conflict do nothing
            """, day, productId);
        jdbc.update("""
            update operations.visit_product_daily
               set view_count = view_count + 1
             where visit_day = ? and product_id = ?
            """, day, productId);
    }

    @Override
    public List<ProductViews> topProducts(LocalDate from, LocalDate to) {
        return jdbc.query("""
            select product_id, sum(view_count) as views
              from operations.visit_product_daily
             where visit_day between ? and ?
             group by product_id
             order by views desc, product_id asc
             limit 20
            """, (row, index) -> new ProductViews(row.getLong("product_id"), row.getLong("views")), from, to);
    }

    @Override
    public List<DailyViews> dailyProduct(long productId, LocalDate from, LocalDate to) {
        return jdbc.query("""
            select visit_day, view_count
              from operations.visit_product_daily
             where product_id = ? and visit_day between ? and ?
             order by visit_day
            """, (row, index) -> new DailyViews(row.getObject("visit_day", LocalDate.class), row.getLong("view_count")),
            productId, from, to);
    }

    @Override
    @Transactional
    public void purgeBefore(LocalDate receiptCutoff, LocalDate aggregateCutoff) {
        jdbc.update("delete from operations.visit_event_receipt where visit_day < ?", receiptCutoff);
        jdbc.update("delete from operations.visit_product_daily where visit_day < ?", aggregateCutoff);
    }
}
