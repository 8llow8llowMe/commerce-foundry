package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.mapper.OrderStockRestorationMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Origin;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 실제 PostgreSQL 17에서 작업원장 CAS·트랜잭션·유니크·CHECK 계약을 검증한다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
@Import({OrderServiceFeaturesConfig.class, QuerydslConfigurer.class, OrderStockRestorationRepositoryAdapter.class, OrderStockRestorationMapperImpl.class})
class OrderStockRestorationRepositoryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00.123456789Z");
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");
    @Autowired OrderStockRestorationRepositoryPort port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clear() { jdbc.update("delete from orders.order_stock_restoration"); }

    @Test
    void persistReadMicrosAndUniqueKeys() {
        OrderStockRestoration saved = tx(() -> port.insert(pending(1, NOW)));
        assertThat(saved.isNew()).isFalse();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(port.existsByClaimId(saved.claimId())).isTrue();
        assertThat(port.findDueRestorations(NOW, 10).getFirst()).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(saved);
        assertThat(saved.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-18T00:00:00.123456Z"));
        assertThatThrownBy(() -> tx(() -> port.insert(new OrderStockRestoration(2, 10, 20, 99, saved.claimId(), Origin.PAYMENT_RECORD,
            Status.RESTORE_PENDING, null, 0, NOW, null, null)))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx(() -> port.insert(new OrderStockRestoration(3, 10, 20, saved.stockReservationId(), 99, Origin.PAYMENT_RECORD,
            Status.RESTORE_PENDING, null, 0, NOW, null, null)))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx(() -> port.insert(saved))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dueBoundarySortLimitAndTerminalExclusion() {
        tx(() -> { for (long id : List.of(3L, 1L, 2L)) { port.insert(pending(id, NOW)); } return null; });
        tx(() -> port.insert(pending(4, NOW.plusSeconds(1))));
        tx(() -> port.insert(pending(5, NOW.minusSeconds(1))));
        assertThat(port.findDueRestorations(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS).minusNanos(1000), 10))
            .extracting(OrderStockRestoration::id).containsExactly(5L);
        tx(() -> port.markRestored(105, NOW));
        assertThat(port.findDueRestorations(NOW, 2)).extracting(OrderStockRestoration::id).containsExactly(1L, 2L);
        OrderStockRestoration first = port.findDueRestorations(NOW, 10).getFirst();
        tx(() -> port.hold(first, first.hold("PRODUCT_028"), NOW));
        tx(() -> port.markRestored(102, NOW));
        assertThat(port.findDueRestorations(NOW, 10)).extracting(OrderStockRestoration::id).containsExactly(3L);
    }

    @Test
    void twoIndependentTransactionsHaveOneClaimWinnerAndStaleHoldLoses() throws Exception {
        OrderStockRestoration before = tx(() -> port.insert(pending(1, NOW)));
        OrderStockRestoration claimed = before.claimAttempt(NOW, Duration.ofSeconds(1), Duration.ofMinutes(1));
        assertThat(race(() -> port.claim(before, claimed, NOW), () -> port.claim(before, claimed, NOW)))
            .containsExactlyInAnyOrder(true, false);
        assertThat(tx(() -> port.hold(before, before.hold("PRODUCT_027"), NOW))).isFalse();
        OrderStockRestoration reclaimed = claimed.claimAttempt(claimed.nextAttemptAt(), Duration.ofSeconds(1), Duration.ofMinutes(1));
        assertThat(tx(() -> port.claim(claimed, reclaimed, claimed.nextAttemptAt()))).isTrue();
        assertThat(tx(() -> port.hold(claimed, claimed.hold("PRODUCT_027"), NOW))).isFalse();
        assertThat(tx(() -> port.hold(reclaimed, reclaimed.hold("PRODUCT_028"), NOW))).isTrue();
        assertThat(tx(() -> port.markRestored(before.claimId(), NOW))).isTrue();
        assertThat(tx(() -> port.markRestored(before.claimId(), NOW))).isFalse();
        assertThat(state()).isEqualTo("RESTORED:2");
        assertThat(jdbc.queryForObject("select hold_code from orders.order_stock_restoration", String.class)).isNull();
    }

    @Test
    void completionRacingHoldAlwaysEndsRestored() throws Exception {
        OrderStockRestoration before = tx(() -> port.insert(pending(1, NOW)));
        List<Boolean> results = race(() -> port.hold(before, before.hold("PRODUCT_029"), NOW), () -> port.markRestored(before.claimId(), NOW));
        assertThat(results.get(1)).isTrue();
        assertThat(state()).isEqualTo("RESTORED:0");
        assertThat(tx(() -> port.hold(before, before.hold("PRODUCT_028"), NOW))).isFalse();
    }

    @Test
    void insertClaimHoldAndCompletionRollbackAndCommitVisibility() {
        visibility(() -> port.insert(pending(1, NOW)), "MISSING", "RESTORE_PENDING:0");
        OrderStockRestoration before = port.findDueRestorations(NOW, 1).getFirst();
        OrderStockRestoration claimed = before.claimAttempt(NOW, Duration.ofSeconds(1), Duration.ofMinutes(1));
        visibility(() -> port.claim(before, claimed, NOW), "RESTORE_PENDING:0", "RESTORE_PENDING:1");
        visibility(() -> port.hold(claimed, claimed.hold("PRODUCT_028"), NOW), "RESTORE_PENDING:1", "HELD:1");
        visibility(() -> port.markRestored(before.claimId(), NOW), "HELD:1", "RESTORED:1");
        assertThat(jdbc.queryForObject("select restored_at from orders.order_stock_restoration", java.sql.Timestamp.class).toInstant())
            .isEqualTo(Instant.parse("2026-11-18T00:00:00.123456Z"));
    }

    @Test
    void everyWriteRequiresAnExistingTransaction() {
        OrderStockRestoration before = pending(1, NOW);
        OrderStockRestoration claimed = before.claimAttempt(NOW, Duration.ofSeconds(1), Duration.ofMinutes(1));
        for (Runnable write : List.<Runnable>of(() -> port.insert(before), () -> port.claim(before, claimed, NOW),
            () -> port.hold(before, before.hold("PRODUCT_028"), NOW), () -> port.markRestored(before.claimId(), NOW))) {
            assertThatThrownBy(write::run).isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    @Test
    void databaseRejectsInvalidStateAttemptsAndFutureEnums() {
        tx(() -> port.insert(pending(1, NOW)));
        for (String assignment : List.of("attempts=-1", "status='HELD'", "status='RESTORED'", "hold_code=''", "hold_code=' '",
            "status='PROBE_PENDING'", "origin='DEFERRED_PROBE'")) {
            assertThatThrownBy(() -> jdbc.update("update orders.order_stock_restoration set " + assignment))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private void visibility(Supplier<?> mutation, String oldState, String newState) {
        TransactionTemplate template = template();
        template.executeWithoutResult(status -> {
            mutation.get();
            assertThat(tx(this::state)).isEqualTo(oldState);
            status.setRollbackOnly();
        });
        assertThat(state()).isEqualTo(oldState);
        template.executeWithoutResult(status -> {
            mutation.get();
            assertThat(tx(this::state)).isEqualTo(oldState);
        });
        assertThat(tx(this::state)).isEqualTo(newState);
    }

    private String state() {
        return jdbc.query("select status || ':' || attempts from orders.order_stock_restoration order by id", (rs, row) -> rs.getString(1))
            .stream().findFirst().orElse("MISSING");
    }

    private List<Boolean> race(Supplier<Boolean> first, Supplier<Boolean> second) throws Exception {
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> left = () -> tx(() -> { await(ready); return first.get(); });
            Callable<Boolean> right = () -> tx(() -> { await(ready); return second.get(); });
            var a = executor.submit(left);
            var b = executor.submit(right);
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch ready) {
        ready.countDown();
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("workers did not start"); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private <T> T tx(Supplier<T> action) { return template().execute(status -> action.get()); }

    private TransactionTemplate template() {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(10);
        return template;
    }

    private static OrderStockRestoration pending(long id, Instant due) {
        return new OrderStockRestoration(id, 10, 20, id + 200, id + 100, Origin.PAYMENT_RECORD, Status.RESTORE_PENDING, null, 0, due, null, null);
    }
}
