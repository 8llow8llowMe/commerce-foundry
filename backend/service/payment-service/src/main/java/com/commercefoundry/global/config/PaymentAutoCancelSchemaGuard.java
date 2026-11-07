package com.commercefoundry.global.config;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs even with ddl-auto=none. Only the separate automatic cursor seeds are inspected. */
public class PaymentAutoCancelSchemaGuard implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;
    public PaymentAutoCancelSchemaGuard(DataSource source) { jdbc = new JdbcTemplate(source); }
    @Override
    public void afterSingletonsInstantiated() {
        try {
            jdbc.queryForList("select lane,last_id,cycle_upper_id,due_before,version,created_at,updated_at from payment.auto_cancel_cursor where 1=0");
            jdbc.queryForList("select payment_id,claim_id,pg_key,amount,currency,state,attempts,next_attempt_at,lease_until,lease_token,"
                + "first_attempt_at,transaction_key,manual_reason,version,created_at,updated_at from payment.payment_auto_cancel where 1=0");
            jdbc.queryForList("select claim_id,payment_id,amount,currency,status,version,created_at,updated_at from payment.payment_cancel where 1=0");
            jdbc.queryForList("select name,lock_until,locked_at,locked_by from payment.shedlock where 1=0");
            var lanes = jdbc.queryForList("select lane from payment.auto_cancel_cursor order by lane", String.class);
            if (!lanes.equals(List.of("LATE_APPROVED", "PENDING", "REJECTED"))) throw new IllegalStateException("Invalid automatic cursor seeds");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("payment.auto-cancel.enabled requires automatic cursor seeds, positive claim metadata, ledger and payment.shedlock DDL");
        }
    }
}
