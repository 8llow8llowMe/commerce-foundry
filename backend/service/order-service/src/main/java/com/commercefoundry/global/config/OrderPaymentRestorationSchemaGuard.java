package com.commercefoundry.global.config;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Hibernate의 ddl-auto와 독립적으로 스윕에 필요한 테이블과 컬럼을 확인한다. */
public final class OrderPaymentRestorationSchemaGuard implements SmartInitializingSingleton {

    private static final Map<String, String> REQUIRED_COLUMNS = Map.of(
        "order_stock_restoration", "id,created_at,updated_at,attempts,claim_id,hold_code,next_attempt_at,order_id,origin,payment_id,"
            + "restored_at,status,stock_reservation_id",
        "order_payment", "id,created_at,updated_at,approved_at,event_id,member_id,order_id,paid_order_id,payment_id,restoration_claim_id,"
            + "restored_at,result,stock_reservation_id,total_amount,total_currency",
        "outbox", "id,created_at,updated_at,aggregate_id,aggregate_type,available_at,event_type,last_error_code,payload,publish_attempts,"
            + "published_at,schema_version,version",
        "shedlock", "name,lock_until,locked_at,locked_by"
    );
    private final JdbcTemplate jdbc;

    OrderPaymentRestorationSchemaGuard(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterSingletonsInstantiated() {
        REQUIRED_COLUMNS.forEach((table, columns) -> {
            try {
                jdbc.queryForList("select " + columns + " from orders." + table + " where 1=0");
            } catch (DataAccessException failure) {
                throw new IllegalStateException("order.payment-restoration.sweep.enabled requires orders." + table + " DDL");
            }
        });
    }
}
