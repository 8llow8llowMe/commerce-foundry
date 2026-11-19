package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.commercefoundry.domainlayer.order.adapter.in.scheduler.OrderPaymentRestorationSweepScheduler;
import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationSweepUseCase;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
class OrderPaymentRestorationLockPostgresTest {

    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Clock CLOCK = Clock.systemUTC();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    private DataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareDataSource() {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from orders.shedlock");
    }

    @Test
    void independentProvidersExcludeEachOtherAndAllowAnotherLockName() {
        var first = new OrderPaymentRestorationLock(source, CLOCK);
        var second = new OrderPaymentRestorationLock(source, CLOCK);
        var relay = new JdbcLockProvider(source, "orders.shedlock");

        assertThat(first.runIfAcquired(LEASE, () -> {
            assertThat(second.runIfAcquired(LEASE, () -> { throw new AssertionError("락을 얻지 못하면 실행할 수 없다"); })).isFalse();
            var relayLock = relay.lock(new LockConfiguration(CLOCK.instant(), "test-relay", LEASE, Duration.ZERO)).orElseThrow();
            relayLock.unlock();
        })).isTrue();
        assertThat(second.runIfAcquired(LEASE, () -> { })).isTrue();
    }

    @Test
    void expiredLeaseCanBeAcquiredThroughAnotherProvider() {
        var first = new JdbcLockProvider(source, "orders.shedlock");
        var second = new OrderPaymentRestorationLock(source, CLOCK);
        // 기존 워커가 사라져 unlock하지 않은 행을 만든다.
        first.lock(new LockConfiguration(CLOCK.instant(), OrderPaymentRestorationLock.NAME, LEASE, Duration.ZERO)).orElseThrow();
        jdbc.update("update orders.shedlock set lock_until = now() - interval '1 second' where name = ?", OrderPaymentRestorationLock.NAME);

        assertThat(second.runIfAcquired(LEASE, () -> { })).isTrue();
    }

    @Test
    void callbackFailureReleasesLockForAnotherProvider() {
        var first = new OrderPaymentRestorationLock(source, CLOCK);
        var second = new OrderPaymentRestorationLock(source, CLOCK);
        var failure = new IllegalStateException("test work failure");

        assertThatThrownBy(() -> first.runIfAcquired(LEASE, () -> { throw failure; })).isSameAs(failure);
        assertThat(second.runIfAcquired(LEASE, () -> { })).isTrue();
    }

    @Test
    void inaccessibleLockTablePreventsWork() {
        var lock = new OrderPaymentRestorationLock(source, CLOCK);
        var executed = new AtomicBoolean();
        jdbc.execute("alter table orders.shedlock rename to test_hidden_shedlock");
        try {
            assertThatThrownBy(() -> lock.runIfAcquired(LEASE, () -> executed.set(true))).isInstanceOf(RuntimeException.class);
            assertThat(executed).isFalse();
        } finally {
            jdbc.execute("alter table orders.test_hidden_shedlock rename to shedlock");
        }
    }

    @Test
    void enabledGateWithCurrentSchemaLeavesMessagingProviderSelectionAlone() {
        var messaging = new JdbcLockProvider(source, "orders.shedlock");
        enabledContext().withBean(LockProvider.class, () -> messaging).run(context -> {
            assertThat(context).hasNotFailed()
                .hasSingleBean(OrderPaymentRestorationLock.class)
                .hasSingleBean(OrderPaymentRestorationSchemaGuard.class)
                .hasSingleBean(OrderPaymentRestorationSweepScheduler.class)
                .hasSingleBean(LockProvider.class);
            assertThat(context.getBean(LockProvider.class)).isSameAs(messaging);
        });
    }

    @ParameterizedTest
    @CsvSource({"order_stock_restoration,next_attempt_at", "order_payment,restored_at", "outbox,payload", "shedlock,lock_until"})
    void enabledGateFailsStartupWhenRequiredColumnIsMissing(String table, String column) {
        String relation = "orders." + table;
        jdbc.execute("alter table " + relation + " rename column " + column + " to test_hidden_column");
        try {
            enabledContext().run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("IllegalStateException: order.payment-restoration.sweep.enabled requires "
                    + relation + " DDL");
            });
        } finally {
            jdbc.execute("alter table " + relation + " rename column test_hidden_column to " + column);
        }
    }

    private ApplicationContextRunner enabledContext() {
        return new ApplicationContextRunner().withUserConfiguration(OrderPaymentRestorationLockConfig.class)
            .withPropertyValues("order.payment-restoration.sweep.enabled=true", "spring.jpa.hibernate.ddl-auto=none",
                "spring.cloud.openfeign.client.config.default.connect-timeout=2000",
                "spring.cloud.openfeign.client.config.default.read-timeout=3000")
            .withBean(PaymentRestorationSweepUseCase.class, () -> (batch, initial, maximum) -> {})
            .withBean(DataSource.class, () -> source)
            .withBean(Clock.class, () -> CLOCK);
    }
}
