package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.adapter.in.scheduler.OrderReconciliationScheduler;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderReconciliationSchedulerUseCase;
import com.commercefoundry.global.properties.PaymentOrderReconciliationProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PaymentOrderReconciliationConfigTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Properties.class, PaymentOrderReconciliationConfig.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(OrderReconciliationSchedulerUseCase.class, () -> mock(OrderReconciliationSchedulerUseCase.class));

    @Test
    void absentOrFalseGateCreatesNoSchedulerGuardOrLockProviderAndTouchesNoDatabase() {
        var source = mock(DataSource.class);
        for (String value : new String[]{"false", ""}) {
            var context = runner.withBean(DataSource.class, () -> source);
            if (!value.isEmpty()) context = context.withPropertyValues("payment.order-result.reconciliation.enabled=" + value);
            context.run(c -> {
                assertThat(c).hasNotFailed().doesNotHaveBean(OrderReconciliationScheduler.class)
                    .doesNotHaveBean(PaymentOrderReconciliationSchemaGuard.class).doesNotHaveBean(net.javacrumbs.shedlock.core.LockProvider.class);
                assertThat(c.getBean(PaymentOrderReconciliationProperties.class).enabled()).isFalse();
            });
        }
        verifyNoInteractions(source);
    }

    @Test
    void enabledConfigurationRequiresTablesAndBothSeedsEvenWithDdlNone() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:reconcile-guard;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("drop all objects");
        var enabled = runner.withBean(DataSource.class, () -> source)
            .withPropertyValues("payment.order-result.reconciliation.enabled=true", "commerce.messaging.outbox.relay.lock-table=payment.shedlock");
        enabled.run(c -> assertThat(c).hasFailed());
        jdbc.execute("create schema payment");
        jdbc.execute("create table payment.order_reconciliation_cursor(lane varchar, last_id bigint,cycle_upper_id bigint,"
            + "approved_before timestamp,version bigint,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table payment.shedlock(name varchar,lock_until timestamp,locked_at timestamp,locked_by varchar)");
        jdbc.execute("create table payment.processed_event(consumer_group varchar,event_id uuid,event_type varchar,processed_at timestamp)");
        enabled.run(c -> assertThat(c).hasFailed());
        jdbc.update("insert into payment.order_reconciliation_cursor(lane) values ('RECONCILE'),('REJECTED')");
        enabled.run(c -> assertThat(c).hasFailed());
        jdbc.execute("alter table payment.processed_event add column id bigint");
        enabled.run(c -> assertThat(c).hasFailed());
        jdbc.execute("alter table payment.processed_event add column created_at timestamp");
        enabled.run(c -> assertThat(c).hasFailed());
        jdbc.execute("alter table payment.processed_event add column updated_at timestamp");
        enabled.run(c -> assertThat(c).hasNotFailed().hasSingleBean(OrderReconciliationScheduler.class)
            .hasSingleBean(PaymentOrderReconciliationSchemaGuard.class).doesNotHaveBean(net.javacrumbs.shedlock.core.LockProvider.class));
        enabled.withPropertyValues("commerce.messaging.outbox.relay.lock-table=other.shedlock")
            .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void schemaProbeFailureDoesNotExposeDatabaseMessage() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new java.sql.SQLException("SENTINEL_DB_MESSAGE"));
        runner.withBean(DataSource.class, () -> source)
            .withPropertyValues("payment.order-result.reconciliation.enabled=true", "commerce.messaging.outbox.relay.lock-table=payment.shedlock")
            .run(c -> {
                assertThat(c).hasFailed();
                var trace = new java.io.StringWriter();
                c.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
                assertThat(trace.toString()).doesNotContain("SENTINEL_DB_MESSAGE");
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PaymentOrderReconciliationProperties.class, com.commercefoundry.messaging.properties.MessagingProperties.class})
    static class Properties { }
}
