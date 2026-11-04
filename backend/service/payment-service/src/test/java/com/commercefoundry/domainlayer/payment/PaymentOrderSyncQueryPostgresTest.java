package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentAnomalyObservationAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentOrderSyncMetrics;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentAnomalyObservationAdapter.class})
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.commercefoundry.domainlayer.payment.PaymentOrderSyncPlanSupport$Capture"
})
class PaymentOrderSyncQueryPostgresTest extends PaymentOrderSyncQueryContract {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired DataSource dataSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void selectTimeoutCancelsRollsBackAndSamePooledConnectionRecovers() throws Exception {
        int backend = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        var registry = new SimpleMeterRegistry();
        try (Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement()) {
                    statement.execute("lock table payment.payment in access exclusive mode");
                }
                List<Runnable> queries = List.of(
                    () -> repository.countAwaitingOrderReply(HOLDING, NOW),
                    () -> repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING, NOW),
                    () -> repository.countOrderRejected(HOLDING));
                for (Runnable query : queries) {
                    long started = System.nanoTime();
                    Throwable failure = catchThrowable(query::run);
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    assertThat(failure).isNotNull();
                    assertThat(hasCancellation(failure)).isTrue();
                    assertThat(elapsedMs).isBetween(500L, 10_000L);
                    assertIdle(blocker, backend); // 질의 실패 후 저장소 트랜잭션의 rollback을 확인한다.
                    System.out.println("C3a-2 PG timeout SQLSTATE=57014 rollback=idle elapsedMs=" + elapsedMs);
                }
                new PaymentOrderSyncMetrics(repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5)).bindTo(registry);
                assertThat(registry.get(PaymentOrderSyncMetrics.PENDING).gauge().value()).isNaN();
                assertThat(registry.get(PaymentOrderSyncMetrics.OBSERVATION_FAILURES).counter().count()).isEqualTo(1);
                assertIdle(blocker, backend);
            } finally {
                blocker.rollback();
            }
            assertThat(repository.countAwaitingOrderReply(HOLDING, NOW)).isZero();
            assertThat(repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING, NOW)).isNull();
            assertThat(repository.countOrderRejected(HOLDING)).isZero();
            assertThat(registry.get(PaymentOrderSyncMetrics.PENDING).gauge().value()).isZero();
            assertThat(registry.get(PaymentOrderSyncMetrics.OBSERVATION_FAILURES).counter().count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select pg_backend_pid()", Integer.class)).isEqualTo(backend);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(connection.getAutoCommit()).isTrue();
            }
            System.out.println("C3a-2 PG timeout recovery=same-pooled-connection healthy-gauge=0");
        } finally {
            registry.close();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTenThousandAndHundredThousandRowPlans() throws Exception {
        new PaymentOrderSyncPlanSupport(jdbc, repository).measure();
    }

    private static boolean hasCancellation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private static void assertIdle(Connection observer, int backend) throws SQLException {
        try (var statement = observer.createStatement()) {
            statement.execute("select pg_stat_clear_snapshot()");
        }
        try (var statement = observer.prepareStatement("select state from pg_stat_activity where pid=?")) {
            statement.setInt(1, backend);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("idle");
            }
        }
    }
}
