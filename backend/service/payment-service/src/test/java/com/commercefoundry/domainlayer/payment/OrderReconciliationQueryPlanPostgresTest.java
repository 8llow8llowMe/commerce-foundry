package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.OrderReconciliationScanAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.OrderReconciliationCursorEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.OrderReconciliationCursorRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 편향된 실제 PG17 후보 질의와 실패 후 커서·연결 회복을 검증한다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentServiceFeaturesConfig.class, OrderReconciliationScanAdapter.class})
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.datasource.hikari.maximum-pool-size=1", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.commercefoundry.domainlayer.payment.PaymentOrderSyncPlanSupport$Capture"})
class OrderReconciliationQueryPlanPostgresTest {
    private static final Instant CUTOFF = PaymentOrderSyncQueryContract.NOW;
    private static final List<Long> FIRST = java.util.stream.LongStream.rangeClosed(99_981, 99_990).boxed().toList();
    private static final List<Long> SECOND = java.util.stream.LongStream.rangeClosed(99_991, 100_000).boxed().toList();
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired PaymentRepository payments;
    @Autowired OrderReconciliationScanAdapter scan;
    @Autowired OrderReconciliationCursorRepository cursors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void resetCursors() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            cursors.deleteAllInBatch();
            cursors.save(new OrderReconciliationCursorEntity(OrderReconciliationLane.RECONCILE, Instant.EPOCH));
            cursors.save(new OrderReconciliationCursorEntity(OrderReconciliationLane.REJECTED, Instant.EPOCH));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"reconcile-sparse-tail", "rejected-sparse-tail"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void skewedHundredThousandRowKeysetUsesBoundedQueryPlan(String scenario) {
        new PaymentOrderSyncPlanSupport(jdbc, payments).fixture(100_000, scenario);
        jdbc.execute("analyze payment.payment");
        boolean pending = scenario.equals("reconcile-sparse-tail");
        try {
            for (int repeat = 0; repeat < 2; repeat++) {
                var first = measuredPage(pending, 0, FIRST, repeat);
                measuredPage(pending, first.getLast(), SECOND, repeat);
            }
            assertThat(query(pending, SECOND.getLast())).isEmpty();
        } finally {
            jdbc.update("delete from payment.payment");
        }
    }

    private List<Long> measuredPage(boolean pending, long afterId, List<Long> expected, int repeat) {
        PaymentOrderSyncPlanSupport.Capture.SQL.remove();
        long started = System.nanoTime();
        var ids = query(pending, afterId);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(ids).containsExactlyElementsOf(expected);
        // JPQL의 2s query hint가 DB 대기를 제한한다. JVM 전체 벽시계에는 여유를 둔다.
        assertThat(elapsedMs).isLessThan(5_000);
        String sql = PaymentOrderSyncPlanSupport.Capture.SQL.get();
        assertThat(sql).isNotNull().contains("payment", "order by");
        var plan = jdbc.query(connection -> {
            var statement = connection.prepareStatement("explain (analyze, buffers) " + sql);
            statement.setString(1, "DONE");
            statement.setString(2, "PARTIAL_CANCELED");
            int index = 3;
            if (pending) statement.setTimestamp(index++, Timestamp.from(CUTOFF));
            statement.setLong(index++, afterId);
            statement.setLong(index++, 100_000);
            statement.setInt(index, 10);
            statement.setQueryTimeout(2);
            return statement;
        }, (rows, rowNumber) -> rows.getString(1));
        assertThat(plan).anyMatch(line -> line.contains("actual time="));
        assertThat(plan).anyMatch(line -> line.contains("Buffers:"));
        System.out.println("#591 PG lane=" + (pending ? "RECONCILE" : "REJECTED") + " repeat=" + repeat
            + " elapsedMs=" + elapsedMs + " rows=" + ids.size() + " sql=" + sql
            + " bindings=[DONE,PARTIAL_CANCELED" + (pending ? ",synthetic-cutoff=" + CUTOFF : "")
            + ",afterId=" + afterId + ",upperId=100000,limit=10]");
        plan.forEach(line -> System.out.println("#591 PG plan " + line));
        return ids;
    }

    private List<Long> query(boolean pending, long afterId) {
        var statuses = PaymentOrderSyncQueryContract.HOLDING;
        return pending ? payments.findReconciliationIds(statuses, CUTOFF, afterId, 100_000, PageRequest.of(0, 10))
            : payments.findRejectedReconciliationIds(statuses, afterId, 100_000, PageRequest.of(0, 10));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockedCandidateQueryTimesOutWithoutAdvancingCursorAndSameConnectionRecovers() throws Exception {
        new PaymentOrderSyncPlanSupport(jdbc, payments).fixture(100_000, "reconcile-sparse-tail");
        jdbc.execute("analyze payment.payment");
        try {
            assertThat(scan.reserveBatch(OrderReconciliationLane.RECONCILE, CUTOFF, 10)).containsExactlyElementsOf(FIRST);
            var before = cursorState();
            long beforeVersion = jdbc.queryForObject("select version from payment.order_reconciliation_cursor where lane='RECONCILE'", Long.class);
            int backend = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
            try (var blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) {
                    statement.execute("lock table payment.payment in access exclusive mode");
                }
                long started = System.nanoTime();
                Throwable failure = catchThrowable(() -> scan.reserveBatch(OrderReconciliationLane.RECONCILE, CUTOFF, 10));
                assertThat(failure).isNotNull();
                boolean canceled = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) canceled = true;
                }
                assertThat(canceled).isTrue();
                // 후보 SELECT의 2s hint와 예약 TX의 5s timeout을 구별한다.
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(4_000);
                blocker.rollback();
            }
            assertThat(cursorState()).isEqualTo(before);
            assertThat(jdbc.queryForObject("select pg_backend_pid()", Integer.class)).isEqualTo(backend);
            assertThat(scan.reserveBatch(OrderReconciliationLane.RECONCILE, CUTOFF, 10)).containsExactlyElementsOf(SECOND);
            assertThat(jdbc.queryForObject("select last_id from payment.order_reconciliation_cursor where lane='RECONCILE'", Long.class))
                .isEqualTo(100_000L);
            assertThat(jdbc.queryForObject("select version from payment.order_reconciliation_cursor where lane='RECONCILE'", Long.class))
                .isGreaterThan(beforeVersion);
        } finally {
            jdbc.update("delete from payment.payment");
        }
    }

    private List<java.util.Map<String, Object>> cursorState() {
        return jdbc.queryForList("select last_id,cycle_upper_id,approved_before,version,updated_at"
            + " from payment.order_reconciliation_cursor where lane='RECONCILE'");
    }
}
