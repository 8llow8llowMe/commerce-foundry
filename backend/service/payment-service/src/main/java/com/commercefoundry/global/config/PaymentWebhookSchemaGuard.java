package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.PaymentWebhookProperties;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentWebhookSchemaGuard implements SmartInitializingSingleton {
    private final DataSource dataSource;
    private final PaymentWebhookProperties properties;
    @Override public void afterSingletonsInstantiated() {
        if (!properties.enabled()) { return; }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            // 상수만 사용하며 행을 읽지 않는다. 테이블·컬럼 DDL 누락이면 활성 기동을 중단한다.
            statement.execute("select id,payment_id,fingerprint,state,attempts,available_at,claim_token,reason,received_at,updated_at,version "
                + "from payment.payment_webhook_inbox where 1=0");
            statement.execute("select 1 from payment.outbox where 1=0");
        } catch (SQLException missing) { throw new IllegalStateException("payment webhook requires inbox and outbox DDL before activation"); }
    }
}
