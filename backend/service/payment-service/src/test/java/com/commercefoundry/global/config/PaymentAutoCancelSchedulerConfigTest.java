package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.payment.adapter.in.scheduler.PaymentAutoCancelScheduler;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelSchedulerUseCase;
import com.commercefoundry.global.properties.PaymentAutoCancelSchedulerProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PaymentAutoCancelSchedulerConfigTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Properties.class, PaymentAutoCancelSchedulerConfig.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(PaymentAutoCancelSchedulerUseCase.class, () -> mock(PaymentAutoCancelSchedulerUseCase.class));
    @Test
    void absentOrFalseGateDoesNotCreateSchedulerGuardOrTouchDb() {
        var source = mock(DataSource.class);
        runner.withBean(DataSource.class, () -> source).run(c -> assertThat(c).hasNotFailed()
            .doesNotHaveBean(PaymentAutoCancelScheduler.class).doesNotHaveBean(PaymentAutoCancelSchemaGuard.class));
        for (boolean legacy : new boolean[]{false, true}) {
            runner.withBean(DataSource.class, () -> source)
                .withPropertyValues("payment.auto-cancel.enabled=false", "payment.auto-cancel.scheduler.enabled=" + legacy)
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(PaymentAutoCancelScheduler.class)
                    .doesNotHaveBean(PaymentAutoCancelSchemaGuard.class));
        }
        verifyNoInteractions(source);
    }
    @Test
    void singleEnabledGateRequiresFullSchemaAndExactlyThreeIndependentSeeds() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:auto-cancel-guard;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("drop all objects");
        var on = runner.withBean(DataSource.class, () -> source).withPropertyValues("payment.auto-cancel.enabled=true");
        on.run(c -> assertThat(c).hasFailed());
        jdbc.execute("create schema payment");
        jdbc.execute("create table payment.auto_cancel_cursor(lane varchar,last_id bigint,cycle_upper_id bigint,due_before timestamp,"
            + "version bigint,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table payment.payment_auto_cancel(payment_id bigint,claim_id bigint,pg_key varchar,amount bigint,currency varchar,state varchar,"
            + "attempts int,next_attempt_at timestamp,lease_until timestamp,lease_token varchar,first_attempt_at timestamp,transaction_key varchar,"
            + "manual_reason varchar,version bigint,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table payment.payment_cancel(claim_id bigint,payment_id bigint,amount bigint,currency varchar,status varchar,"
            + "version bigint,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table payment.shedlock(name varchar,lock_until timestamp,locked_at timestamp,locked_by varchar)");
        on.run(c -> assertThat(c).hasFailed());
        jdbc.update("insert into payment.auto_cancel_cursor(lane) values ('REJECTED'),('LATE_APPROVED')");
        on.run(c -> assertThat(c).hasFailed());
        jdbc.update("insert into payment.auto_cancel_cursor(lane) values ('PENDING')");
        on.run(c -> assertThat(c).hasNotFailed().hasSingleBean(PaymentAutoCancelScheduler.class)
            .hasSingleBean(PaymentAutoCancelSchemaGuard.class).doesNotHaveBean(net.javacrumbs.shedlock.core.LockProvider.class));
        for (boolean legacy : new boolean[]{false, true}) {
            on.withPropertyValues("payment.auto-cancel.scheduler.enabled=" + legacy)
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(PaymentAutoCancelScheduler.class).hasSingleBean(PaymentAutoCancelSchemaGuard.class);
                    assertThat(c.getBean(PaymentAutoCancelSchedulerProperties.class).enabled()).isTrue();
                    assertThat(c.getBean(PaymentAutoCancelSchedulerProperties.class).lockAtMostFor()).isEqualTo(java.time.Duration.ofMinutes(15));
                });
        }
        jdbc.update("insert into payment.auto_cancel_cursor(lane) values ('UNRECOGNIZED')");
        on.run(c -> assertThat(c).hasFailed());
    }
    @Test
    void nestedBudgetsBindUnderSingleGateAndInvalidBudgetFailsEvenWhenOff() {
        runner.withPropertyValues("payment.auto-cancel.enabled=false", "payment.auto-cancel.scheduler.page-size=50",
            "payment.auto-cancel.scheduler.max-pages-per-lane=2", "payment.auto-cancel.scheduler.lock-at-most-for=4h")
            .run(c -> {
                assertThat(c).hasNotFailed();
                var properties = c.getBean(PaymentAutoCancelSchedulerProperties.class);
                assertThat(properties.enabled()).isFalse();
                assertThat(properties.pageSize()).isEqualTo(50);
                assertThat(properties.maxPagesPerLane()).isEqualTo(2);
            });
        runner.withPropertyValues("payment.auto-cancel.scheduler.page-size=51").run(c -> assertThat(c).hasFailed());
    }

    @Test
    void startupFailureDoesNotRetainRawSqlCause() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new java.sql.SQLException("SENTINEL_PRIVATE"));
        runner.withBean(DataSource.class, () -> source)
            .withPropertyValues("payment.auto-cancel.scheduler.enabled=true", "payment.auto-cancel.enabled=true")
            .run(c -> {
                assertThat(c).hasFailed();
                var trace = new java.io.StringWriter();
                c.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
                assertThat(trace.toString()).doesNotContain("SENTINEL_PRIVATE");
            });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentAutoCancelSchedulerProperties.class)
    static class Properties { }
}
