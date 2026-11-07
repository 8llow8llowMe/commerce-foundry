package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.payment.adapter.in.scheduler.PaymentAutoCancelScheduler;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelSchedulerUseCase;
import com.commercefoundry.global.properties.PaymentAutoCancelSchedulerProperties;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "payment.auto-cancel.enabled", havingValue = "true")
public class PaymentAutoCancelSchedulerConfig {
    @Bean
    PaymentAutoCancelScheduler paymentAutoCancelScheduler(PaymentAutoCancelSchedulerUseCase useCase, DataSource source,
        PaymentAutoCancelSchedulerProperties properties, Clock clock) {
        // Private provider: reuse the operational table without replacing messaging/reconciliation providers.
        return new PaymentAutoCancelScheduler(useCase, new JdbcLockProvider(source, "payment.shedlock"), properties, clock);
    }
    @Bean
    PaymentAutoCancelSchemaGuard paymentAutoCancelSchemaGuard(DataSource source) {
        return new PaymentAutoCancelSchemaGuard(source);
    }
}
