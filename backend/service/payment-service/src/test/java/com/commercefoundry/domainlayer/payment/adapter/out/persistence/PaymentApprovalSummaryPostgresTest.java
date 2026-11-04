package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
class PaymentApprovalSummaryPostgresTest extends PaymentApprovalSummaryQueryContract {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired DataSource dataSource;

    @Test
    void queryTimeoutCancelsTheActualAggregateWhileItsTableIsLocked() throws Exception {
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("lock table payment.payment in access exclusive mode");
                var started = java.time.Instant.now();
                Throwable thrown = catchThrowable(() -> port.summarize(FROM, TO));
                assertThat(thrown).isNotNull();
                assertThat(NestedExceptionUtils.getMostSpecificCause(thrown)).isInstanceOfSatisfying(SQLException.class,
                    exception -> assertThat(exception.getSQLState()).isEqualTo("57014"));
                // PostgreSQL cancellation is caused by the 2 s query hint, not a lock_timeout setting.
                assertThat(Duration.between(started, java.time.Instant.now())).isLessThan(Duration.ofSeconds(10));
            } finally {
                blocker.rollback();
            }
        }
    }
}
