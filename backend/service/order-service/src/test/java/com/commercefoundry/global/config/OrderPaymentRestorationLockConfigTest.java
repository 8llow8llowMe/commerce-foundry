package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.adapter.in.scheduler.OrderPaymentRestorationSweepScheduler;
import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationSweepUseCase;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderPaymentRestorationLockConfigTest {

    private static final String ENABLED = "order.payment-restoration.sweep.enabled";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OrderPaymentRestorationLockConfig.class);

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(ENABLED + "=true", "spring.jpa.hibernate.ddl-auto=none",
            "spring.cloud.openfeign.client.config.default.connect-timeout=2000",
            "spring.cloud.openfeign.client.config.default.read-timeout=3000")
            .withBean(PaymentRestorationSweepUseCase.class, () -> mock(PaymentRestorationSweepUseCase.class))
            .withBean(Clock.class, Clock::systemUTC);
    }

    @Test
    void missingOrFalseGateNeedsNeitherDataSourceNorClock() {
        runner.run(OrderPaymentRestorationLockConfigTest::assertDisabled);
        runner.withPropertyValues(ENABLED + "=false").run(OrderPaymentRestorationLockConfigTest::assertDisabled);
    }

    @Test
    void explicitlyEnabledGateFailsStartupWhenSchemaCannotBeRead() throws SQLException {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("test unavailable"));

        enabled().withBean(DataSource.class, () -> source)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("IllegalStateException: " + ENABLED + " requires orders.");
            });
        verify(source).getConnection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"batch-size=0", "batch-size=11", "batch-size=10", "lock-at-most-for=289s", "interval=0s",
        "initial-backoff=59s", "max-backoff=1m", "lock-at-most-for=2h"})
    void invalidSweepBudgetsFailBeforeScheduling(String option) {
        enabled().withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues("order.payment-restoration.sweep.initial-backoff=2m", "order.payment-restoration.sweep." + option)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("order.payment-restoration.sweep");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"paymentClient.read-timeout=3001", "stockRestorationClient.connect-timeout=0", "default.connect-timeout=2001",
        "default.retryer=feign.Retryer$Default"})
    void invalidEffectiveFeignBudgetFailsBeforeScheduling(String option) {
        enabled().withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues("spring.cloud.openfeign.client.config." + option)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("requires connect <= 2000ms");
            });
    }

    private static void assertDisabled(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed()
            .doesNotHaveBean(OrderPaymentRestorationLock.class)
            .doesNotHaveBean(OrderPaymentRestorationSchemaGuard.class)
            .doesNotHaveBean(OrderPaymentRestorationSweepScheduler.class)
            .doesNotHaveBean(LockProvider.class);
    }
}
