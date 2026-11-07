package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.payment.adapter.in.scheduler.OrderReconciliationScheduler;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderReconciliationSchedulerUseCase;
import com.commercefoundry.global.properties.PaymentOrderReconciliationProperties;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "payment.order-result.reconciliation.enabled", havingValue = "true")
public class PaymentOrderReconciliationConfig {
    @Bean
    OrderReconciliationScheduler orderReconciliationScheduler(OrderReconciliationSchedulerUseCase useCase, DataSource dataSource,
        PaymentOrderReconciliationProperties properties, Clock clock, MessagingProperties messaging) {
        // payment 서비스의 운영 DDL 계약은 payment.shedlock으로 고정한다. core 릴레이와 다른 테이블로 분리하지 않는다.
        if (!"payment.shedlock".equals(messaging.outbox().relay().lockTable())) {
            throw new IllegalStateException("payment reconciliation requires commerce.messaging.outbox.relay.lock-table=payment.shedlock");
        }
        // core 메시징의 LockProvider 선택에 끼어들지 않는다. 테이블만 공유하고 락 이름은 독립이다.
        return new OrderReconciliationScheduler(useCase, new JdbcLockProvider(dataSource, "payment.shedlock"), properties, clock);
    }

    @Bean
    PaymentOrderReconciliationSchemaGuard paymentOrderReconciliationSchemaGuard(DataSource dataSource) {
        return new PaymentOrderReconciliationSchemaGuard(dataSource);
    }
}
