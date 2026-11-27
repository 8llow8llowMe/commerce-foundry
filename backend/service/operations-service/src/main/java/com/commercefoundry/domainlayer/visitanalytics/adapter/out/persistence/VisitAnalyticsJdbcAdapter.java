package com.commercefoundry.domainlayer.visitanalytics.adapter.out.persistence;

import com.commercefoundry.domainlayer.visitanalytics.application.port.out.VisitAnalyticsStore;
import jakarta.persistence.EntityManager;
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
    private final EntityManager entityManager;

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
        entityManager.createQuery("""
            update VisitProductDailyEntity counter
               set counter.viewCount = counter.viewCount + 1
             where counter.visitDay = :day and counter.productId = :productId
            """).setParameter("day", day).setParameter("productId", productId).executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductViews> topProducts(LocalDate from, LocalDate to) {
        return entityManager.createQuery("""
            select counter.productId, sum(counter.viewCount)
              from VisitProductDailyEntity counter
             where counter.visitDay between :from and :to
             group by counter.productId
             order by sum(counter.viewCount) desc, counter.productId asc
            """, ProductViews.class).setParameter("from", from).setParameter("to", to).setMaxResults(20).getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyViews> dailyProduct(long productId, LocalDate from, LocalDate to) {
        return entityManager.createQuery("""
            select counter.visitDay, counter.viewCount
              from VisitProductDailyEntity counter
             where counter.productId = :productId and counter.visitDay between :from and :to
             order by counter.visitDay
            """, DailyViews.class).setParameter("productId", productId).setParameter("from", from).setParameter("to", to).getResultList();
    }

    @Override
    @Transactional
    public void purgeBefore(LocalDate receiptCutoff, LocalDate aggregateCutoff) {
        entityManager.createQuery("delete from VisitEventReceiptEntity receipt where receipt.visitDay < :cutoff")
            .setParameter("cutoff", receiptCutoff).executeUpdate();
        entityManager.createQuery("delete from VisitProductDailyEntity counter where counter.visitDay < :cutoff")
            .setParameter("cutoff", aggregateCutoff).executeUpdate();
    }
}
