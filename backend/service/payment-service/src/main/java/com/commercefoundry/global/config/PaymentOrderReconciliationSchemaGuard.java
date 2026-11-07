package com.commercefoundry.global.config;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;

/** 운영 none에서도 켠 스위퍼가 DDL 누락을 숨기지 않는다. DB 원문/cause는 전파하지 않는다. */
public class PaymentOrderReconciliationSchemaGuard implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;

    public PaymentOrderReconciliationSchemaGuard(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            jdbc.queryForList("select lane,last_id,cycle_upper_id,approved_before,version,created_at,updated_at"
                + " from payment.order_reconciliation_cursor where 1=0");
            jdbc.queryForList("select name,lock_until,locked_at,locked_by from payment.shedlock where 1=0");
            jdbc.queryForList("select id,consumer_group,event_id,event_type,processed_at,created_at,updated_at"
                + " from payment.processed_event where 1=0");
            var lanes = jdbc.queryForList("select lane from payment.order_reconciliation_cursor order by lane", String.class);
            if (!lanes.equals(List.of("RECONCILE", "REJECTED"))) throw new IllegalStateException("Missing cursor seeds");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("payment.order-result.reconciliation requires cursor seeds, inbox and shedlock operational DDL");
        }
    }
}
