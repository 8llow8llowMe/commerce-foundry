package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.PaymentAnomalyAlertScheduler;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.NotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.PaymentAnomalyAlertStateRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Production JDBC ShedLock and PostgreSQL commit behavior; no mocked state processor or transaction manager. */
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "operations.notifications.payment-anomaly-alert.enabled=true",
    "operations.notifications.payment-anomaly-alert.initial-delay=1d",
    "operations.notifications.payment-outbox-alert.enabled=false",
    "commerce.messaging.enabled=false", "operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
class PaymentAnomalyAlertPostgresTest {
    private static final String LOCK_NAME = "operations-payment-anomaly-alert";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @Autowired PaymentAnomalyAlertScheduler scheduler;
    @Autowired PaymentAnomalyAlertStateRepository states;
    @Autowired NotificationRepository notifications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationRecipientClient recipients;
    @MockitoBean PaymentAnomalyObservationPort source;
    @MockitoBean NotificationPushPort push;
    @MockitoBean Clock clock;
    @MockitoBean(name = "paymentAnomalyTicker") LongSupplier ticker;
    @MockitoSpyBean NotificationBatchInsertPort inserts;
    @MockitoSpyBean PaymentAnomalyAlertPushPort delivery;
    private Instant start;

    @BeforeEach
    void setup() {
        states.deleteAll();
        notifications.deleteAll();
        // Retain the row: JdbcLockProvider caches which lock names have already been inserted.
        expireLease();
        start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        when(clock.instant()).thenReturn(start);
        when(ticker.getAsLong()).thenReturn(0L);
        when(recipients.page(null, 100)).thenReturn(Response.success(new NotificationRecipientClient.RecipientPage(List.of("7", "8"))));
    }

    @Test
    void suspendedCallerRollbackCannotUndoIndependentlyCommittedPostgresRows() throws Exception {
        var delivered = new CountDownLatch(2);
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        doAnswer(call -> {
            try {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(notifications.count()).isEqualTo(2);
                assertWatermark(start, PaymentAnomalyPolicy.Status.ACTIVE);
            } catch (Throwable failure) {
                failures.add(failure);
            } finally {
                delivered.countDown();
            }
            return null;
        }).when(push).push(any());
        when(source.observe()).thenAnswer(call -> {
            verify(recipients).page(null, 100);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return sample(start, true);
        });
        new TransactionTemplate(transactions).executeWithoutResult(outer -> {
            scheduler.observe();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            await(delivered);
            assertThat(failures).isEmpty();
            outer.setRollbackOnly();
        });
        assertThat(notifications.count()).isEqualTo(2);
        assertWatermark(start, PaymentAnomalyPolicy.Status.ACTIVE);
    }

    @Test
    void deferredConstraintFailsAtActualCommitAfterFlushAndNeverEnqueues() {
        collect(start, false);
        var before = snapshot();
        var flushReturned = new AtomicBoolean();
        NotificationBatchInsertPort insertTarget;
        try {
            insertTarget = AopTestUtils.getUltimateTargetObject(inserts);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        doAnswer(call -> {
            var result = call.callRealMethod();
            // A deferred constraint must not fire during saveAllAndFlush.
            assertThat(notifications.count()).isEqualTo(2);
            flushReturned.set(true);
            return result;
        }).when(insertTarget).insertAll(any());

        jdbc.execute("create sequence operations.e3b_commit_probe");
        try {
            jdbc.execute("""
                create function operations.e3b_fail_notification_commit() returns trigger language plpgsql as $$
                begin
                    perform nextval('operations.e3b_commit_probe');
                    raise exception 'E3b deferred commit rejection' using errcode = '23514';
                end;
                $$
                """);
            jdbc.execute("""
                create constraint trigger e3b_notification_commit_failure
                after insert on operations.notification deferrable initially deferred
                for each row execute function operations.e3b_fail_notification_commit()
                """);
            collect(start.plusSeconds(1), true);
            assertThat(flushReturned).isTrue();
            // Sequence changes survive rollback, proving the deferred trigger really executed.
            assertThat(jdbc.queryForObject("select is_called from operations.e3b_commit_probe", Boolean.class)).isTrue();
            assertThat(snapshot()).isEqualTo(before);
            assertThat(notifications.count()).isZero();
            verifyNoInteractions(delivery, push);
        } finally {
            jdbc.execute("drop trigger if exists e3b_notification_commit_failure on operations.notification");
            jdbc.execute("drop function if exists operations.e3b_fail_notification_commit()");
            jdbc.execute("drop sequence operations.e3b_commit_probe");
        }

        doCallRealMethod().when(insertTarget).insertAll(any());
        expireLease();
        scheduler.observe();
        assertThat(notifications.count()).isEqualTo(2);
        assertWatermark(start.plusSeconds(1), PaymentAnomalyPolicy.Status.ACTIVE);
        verify(push, timeout(3000).times(2)).push(any());
    }

    @Test
    void actualLeaseExpiryOverlapsCollectorsAndDelayedOldResponseCannotUndoNewerCommit() throws Exception {
        var firstSourceEntered = new CountDownLatch(1);
        var releaseOldResponse = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var newer = start.plusSeconds(1);
        when(source.observe()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() == 1) {
                firstSourceEntered.countDown();
                await(releaseOldResponse);
                return sample(start, false);
            }
            return sample(newer, true);
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldCollector = executor.submit(scheduler::observe);
            try {
                assertThat(firstSourceEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from operations.shedlock where name = ? and lock_until > current_timestamp",
                    Long.class, LOCK_NAME)).isEqualTo(1L);
                scheduler.observe();
                assertThat(calls).hasValue(1);
                assertThat(states.count()).isZero();

                // Expire the real persisted lease while collector A still holds its lock handle and response.
                assertThat(expireLease()).isEqualTo(1);
                when(clock.instant()).thenReturn(newer);
                scheduler.observe();
                assertThat(calls).hasValue(2);
                assertWatermark(newer, PaymentAnomalyPolicy.Status.ACTIVE);
                assertThat(notifications.count()).isEqualTo(2);
                var before = snapshot();
                releaseOldResponse.countDown();
                oldCollector.get(10, TimeUnit.SECONDS);
                assertThat(snapshot()).isEqualTo(before);
                assertThat(notifications.count()).isEqualTo(2);
                verify(inserts, times(1)).insertAll(any());
                verify(delivery, times(1)).enqueue(any());
                verify(push, timeout(3000).times(2)).push(any());
            } finally {
                releaseOldResponse.countDown();
                oldCollector.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private int expireLease() {
        return jdbc.update("update operations.shedlock set lock_until = ? where name = ?",
            Timestamp.from(Instant.now().minusSeconds(1)), LOCK_NAME);
    }

    private void collect(Instant at, boolean active) {
        when(clock.instant()).thenReturn(at);
        doReturn(sample(at, active)).when(source).observe();
        scheduler.observe();
    }

    private static PaymentAnomalyObservation sample(Instant at, boolean active) {
        var empty = new PaymentAnomalyObservation.Fact(0, null);
        var unknown = active ? new PaymentAnomalyObservation.Fact(1, at.minusSeconds(600)) : empty;
        return new PaymentAnomalyObservation(at, unknown, empty, empty, empty, empty, empty);
    }

    private List<List<Object>> snapshot() {
        return states.findAll().stream().sorted(java.util.Comparator.comparing(s -> s.getFamily()))
            .map(s -> java.util.Arrays.<Object>asList(s.getFamily(), s.getStatus(), s.getSeverity(), s.getLastObservedAt(),
                s.getLastAcceptedLocalAt(), s.getFirstDetectedAt(), s.getLastAlertAt(), s.getVersion())).toList();
    }

    private void assertWatermark(Instant at, PaymentAnomalyPolicy.Status unknown) {
        assertThat(states.findAll()).hasSize(5).allSatisfy(row -> assertThat(row.getLastObservedAt()).isEqualTo(at));
        assertThat(states.findById("UNKNOWN").orElseThrow().getStatus()).isEqualTo(unknown);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Collector/delivery barrier timeout"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
