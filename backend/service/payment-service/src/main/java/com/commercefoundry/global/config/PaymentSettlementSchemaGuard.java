package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.PaymentSettlementProperties;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 정산 대사(#219)를 켠 채 운영 DDL 이 빠졌으면 기동을 멈춘다 — 첫 대사 요청에서야 500 으로 드러나지 않게. 꺼져 있으면 보지 않는다.
 * 상수 질의만 쓰고 행을 읽지 않는다. 오류 메시지에 DB 원문을 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PaymentSettlementSchemaGuard implements SmartInitializingSingleton {

    private final DataSource dataSource;
    private final PaymentSettlementProperties properties;

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.enabled()) {
            return;
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("select id,transaction_key,payment_key,pg_order_id,sold_date,paid_out_date,method,amount,fee_amount,pay_out_amount,currency,"
                + "canceled,approved_at,collected_at,created_at,updated_at from payment.pg_settlement_item where 1=0");
            statement.execute("select id,sold_date,status,item_count,pg_amount_total,fee_total,pay_out_total,currency,mismatch_count,collected_at,"
                + "reconciled_at,created_at,updated_at,version from payment.settlement_day where 1=0");
            statement.execute("select id,sold_date,payment_key,payment_id,type,status,resolution,expected_amount,actual_amount,currency,detected_at,"
                + "resolved_at,created_at,updated_at,version from payment.settlement_mismatch where 1=0");
        } catch (SQLException missing) {
            throw new IllegalStateException("payment settlement requires pg_settlement_item, settlement_day and settlement_mismatch DDL before activation");
        }
    }
}
