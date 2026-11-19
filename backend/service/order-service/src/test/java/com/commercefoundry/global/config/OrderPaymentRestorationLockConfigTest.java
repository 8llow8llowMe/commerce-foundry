package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderPaymentRestorationLockConfigTest {

    private static final String ENABLED = "order.payment-restoration.sweep.enabled";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OrderPaymentRestorationLockConfig.class);

    @Test
    void missingOrFalseGateNeedsNeitherDataSourceNorClock() {
        runner.run(OrderPaymentRestorationLockConfigTest::assertDisabled);
        runner.withPropertyValues(ENABLED + "=false").run(OrderPaymentRestorationLockConfigTest::assertDisabled);
    }

    @Test
    void explicitlyEnabledGateFailsStartupWhenSchemaCannotBeRead() throws SQLException {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("test unavailable"));

        runner.withPropertyValues(ENABLED + "=true", "spring.jpa.hibernate.ddl-auto=none")
            .withBean(DataSource.class, () -> source)
            .withBean(Clock.class, Clock::systemUTC)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("IllegalStateException: " + ENABLED + " requires orders.");
            });
        verify(source).getConnection();
    }

    private static void assertDisabled(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed()
            .doesNotHaveBean(OrderPaymentRestorationLock.class)
            .doesNotHaveBean(OrderPaymentRestorationSchemaGuard.class)
            .doesNotHaveBean(LockProvider.class);
    }
}
