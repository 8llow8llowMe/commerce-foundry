package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.*;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.*;
import com.commercefoundry.domainlayer.notification.application.port.out.OutboxAlertStatePort;
import com.commercefoundry.domainlayer.notification.application.info.PaymentOutboxObservationInfo;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentOutboxAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.OutboxAlertState;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
class PaymentOutboxAlertPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");
    @Autowired PaymentOutboxAlertStateProcessor processor;
    @Autowired OutboxAlertStateRepository states;
    @Autowired OutboxAlertStatePort port;
    @Autowired NotificationRepository notifications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired net.javacrumbs.shedlock.core.LockProvider locks;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort notificationPort;
    @MockitoBean com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort push;
    @MockitoBean Clock clock;
    @MockitoBean(name = "paymentOutboxAlertTicker") LongSupplier ticker;
    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");
    @BeforeEach
    void resetData() {
        reset(notificationPort, push);
        states.deleteAll(); notifications.deleteAll();
        when(clock.instant()).thenReturn(start);
        when(ticker.getAsLong()).thenReturn(0L);
    }
    void record(Instant observed) {
        processor.accept(new PaymentOutboxObservationInfo(observed, 1, start.minusSeconds(300)), List.of(7L, 8L), start, start, 0);
    }
    @Test
    void actualNowaitLockRejectsConcurrentWriter() throws Exception {
        record(start);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var held = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                states.findLocked().orElseThrow(); locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertThatThrownBy(() -> record(start)).isInstanceOf(org.springframework.dao.DataAccessException.class));
            } finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
        }
        assertThat(notifications.count()).isEqualTo(2);
    }
    @Test
    void absentReadThenCompetingCommitCannotOverwriteOrFanoutAgain() throws Exception {
        var emptyRead = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loser = executor.submit(() -> {
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    assertThat(port.findLocked()).isEmpty();
                    emptyRead.countDown(); await(committed);
                    // Models the decision calculated from an absent row; save MUST INSERT, never re-read/upsert.
                    port.save(new OutboxAlertState(OutboxAlertState.Status.NORMAL, start.minusSeconds(1), start, null, null), true);
                })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            });
            assertThat(emptyRead.await(10, TimeUnit.SECONDS)).isTrue();
            record(start);
            committed.countDown();
            loser.get(10, TimeUnit.SECONDS);
        } finally { committed.countDown(); }
        assertThat(states.findById("PAYMENT").orElseThrow().getLastObservedAt()).isEqualTo(start);
        assertThat(notifications.count()).isEqualTo(2);
    }
    @Test
    void replayOutOfOrderAndStaleCannotResolveIncident() {
        record(start);
        record(start);
        record(start.minusSeconds(1));
        when(clock.instant()).thenReturn(start.plusSeconds(100));
        processor.accept(new PaymentOutboxObservationInfo(start.plusSeconds(1), 0, null), List.of(7L),
            start.plusSeconds(100), start.plusSeconds(100), 0);
        assertThat(states.findById("PAYMENT").orElseThrow().getStatus()).isEqualTo(OutboxAlertState.Status.ACTIVE);
        assertThat(states.findById("PAYMENT").orElseThrow().getLastObservedAt()).isEqualTo(start);
        assertThat(notifications.count()).isEqualTo(2);
    }
    @Test
    void realPostgresFanoutFailureRollsBackExistingWatermarksAndRetrySucceeds() {
        processor.accept(new PaymentOutboxObservationInfo(start, 0, null), List.of(7L), start, start, 0);
        long version = states.findById("PAYMENT").orElseThrow().getVersion();
        when(clock.instant()).thenReturn(start.plusSeconds(1));
        var inserts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i -> {
            if (inserts.incrementAndGet() == 2) throw new IllegalStateException("recipient insert unavailable");
            return i.callRealMethod();
        }).when(notificationPort).insert(any());
        assertThatThrownBy(() -> record(start.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThat(states.findById("PAYMENT").orElseThrow().getVersion()).isEqualTo(version);
        assertThat(states.findById("PAYMENT").orElseThrow().getLastObservedAt()).isEqualTo(start);
        assertThat(notifications.count()).isZero();
        verify(push, never()).push(any());
        reset(notificationPort);
        record(start.plusSeconds(1));
        assertThat(notifications.count()).isEqualTo(2);
        verify(push, timeout(3000).times(2)).push(any());
    }
    @Test
    void expiredShedLockAllowsOverlapButDelayedObservationCannotUndoNewerCommit() {
        var config = new net.javacrumbs.shedlock.core.LockConfiguration(Instant.now(), "operations-payment-outbox-alert",
            Duration.ofMinutes(2), Duration.ZERO);
        var first = locks.lock(config).orElseThrow();
        net.javacrumbs.shedlock.core.SimpleLock second = null;
        try {
            assertThat(locks.lock(config)).isEmpty();
            // Controlled lease expiry, with the original collector still holding its handle.
            jdbc.update("update operations.shedlock set lock_until = ? where name = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), "operations-payment-outbox-alert");
            second = locks.lock(config).orElseThrow();
            when(clock.instant()).thenReturn(start.plusSeconds(1));
            record(start.plusSeconds(1));
            record(start);
            assertThat(states.findById("PAYMENT").orElseThrow().getLastObservedAt()).isEqualTo(start.plusSeconds(1));
            assertThat(notifications.count()).isEqualTo(2);
        } finally {
            if (second != null) second.unlock();
            first.unlock();
        }
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
