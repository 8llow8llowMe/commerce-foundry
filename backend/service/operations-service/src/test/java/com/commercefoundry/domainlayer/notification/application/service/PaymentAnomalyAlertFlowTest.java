package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.PaymentAnomalyAlertScheduler;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.NotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.PaymentAnomalyAlertStateRepository;
import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertLockPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real scheduler/facade/transaction proxies, H2 persistence and asynchronous SSE delivery adapter. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:anomaly-alert-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.notifications.payment-anomaly-alert.enabled=true",
    "operations.notifications.payment-anomaly-alert.initial-delay=1d",
    "operations.notifications.payment-outbox-alert.enabled=false",
    "commerce.messaging.enabled=false", "operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAnomalyAlertFlowTest {
    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");

    @Autowired PaymentAnomalyAlertScheduler scheduler;
    @Autowired PaymentAnomalyAlertSchedulerFacade facade;
    @Autowired PaymentAnomalyAlertStateRepository states;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPushPort streams;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean NotificationRecipientClient recipients;
    @MockitoBean PaymentAnomalyObservationPort source;
    @MockitoBean PaymentAnomalyAlertLockPort lock;
    @MockitoBean Clock clock;
    @MockitoBean(name = "paymentAnomalyTicker") LongSupplier ticker;
    @MockitoSpyBean NotificationBatchInsertPort inserts;
    @MockitoSpyBean PaymentAnomalyAlertPushPort delivery;

    @BeforeEach
    void setup() {
        states.deleteAll();
        notifications.deleteAll();
        when(clock.instant()).thenReturn(START);
        when(ticker.getAsLong()).thenReturn(0L);
        when(recipients.page(null, 100)).thenReturn(Response.success(new NotificationRecipientClient.RecipientPage(List.of("7", "8"))));
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).when(lock).runLocked(any());
    }

    @Test
    void recipientsThenSourceThenCommitAreVisibleFromActualStreamDelivery() throws Exception {
        when(source.observe()).thenAnswer(call -> {
            verify(recipients).page(null, 100);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(states.count()).isZero();
            return sample(START, true);
        });
        try (var probe = new StreamProbe(2, 2, START)) {
            scheduler.observe();
            probe.await();
            assertThat(probe.messages).extracting(NotificationPush::notificationId).doesNotHaveDuplicates();
            assertThat(probe.messages).allSatisfy(message -> {
                assertThat(message.linkPath()).isEqualTo("/admin/payments");
                assertThat(message.read()).isFalse();
            });
        }
        assertWatermark(START, PaymentAnomalyPolicy.Status.ACTIVE);
        assertThat(notifications.findAll()).extracting(n -> n.getRecipientMemberId()).containsExactlyInAnyOrder(7L, 8L);
        verify(delivery).enqueue(any());
    }

    @Test
    void callerTransactionIsSuspendedAndInnerCommitSurvivesOuterRollback() throws Exception {
        when(source.observe()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return sample(START, true);
        });
        try (var probe = new StreamProbe(2, 2, START)) {
            new TransactionTemplate(transactions).executeWithoutResult(outer -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                facade.observe();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                try {
                    // The independent worker reads the committed rows while the caller TX is still open.
                    probe.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                outer.setRollbackOnly();
            });
        }
        assertWatermark(START, PaymentAnomalyPolicy.Status.ACTIVE);
        assertThat(notifications.count()).isEqualTo(2);
    }

    @Test
    void recipientAndSourceFailuresPreserveExistingIncidentWithoutDelivering() throws Exception {
        try (var opened = new StreamProbe(2, 2, START)) {
            collect(START, true);
            opened.await();
        }
        clearInvocations(delivery, source, recipients);
        var before = snapshot();
        when(recipients.page(null, 100)).thenThrow(new IllegalStateException("recipient unavailable"));
        scheduler.observe();
        verifyNoInteractions(source, delivery);
        assertThat(snapshot()).isEqualTo(before);

        doReturn(Response.success(new NotificationRecipientClient.RecipientPage(List.of("7", "8"))))
            .when(recipients).page(null, 100);
        when(source.observe()).thenThrow(new IllegalStateException("source unavailable"));
        scheduler.observe();
        verify(source).observe();
        verifyNoInteractions(delivery);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(notifications.count()).isEqualTo(2);
    }

    @Test
    void failureAfterRealBatchFlushRollsBackStateAndAllRecipientsBeforeRetry() throws Exception {
        collect(START, false);
        var before = snapshot();
        var insertTarget = AopTestUtils.<NotificationBatchInsertPort>getUltimateTargetObject(inserts);
        doAnswer(call -> {
            call.callRealMethod();
            assertThat(notifications.count()).isEqualTo(2);
            throw new IllegalStateException("injected after flush, before commit");
        }).when(insertTarget).insertAll(any());
        collect(START.plusSeconds(1), true);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(notifications.count()).isZero();
        verifyNoInteractions(delivery);

        doCallRealMethod().when(insertTarget).insertAll(any());
        try (var probe = new StreamProbe(2, 2, START.plusSeconds(1))) {
            scheduler.observe();
            probe.await();
        }
        assertWatermark(START.plusSeconds(1), PaymentAnomalyPolicy.Status.ACTIVE);
    }

    @Test
    void postCommitEnqueueFailureKeepsRowsAndReplayDoesNotReinsert() {
        doThrow(new IllegalStateException("queue unavailable")).when(delivery).enqueue(any());
        collect(START, true);
        var before = snapshot();
        var ids = notifications.findAll().stream().map(n -> n.getId()).sorted().toList();
        scheduler.observe();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(notifications.findAll()).extracting(n -> n.getId()).containsExactlyInAnyOrderElementsOf(ids);
        verify(inserts, times(1)).insertAll(any());
        verify(delivery, times(1)).enqueue(any());
    }

    @Test
    void suppressedReplayOlderAndStaleSamplesCannotUndoIncidentThenRecoveryIsDelivered() throws Exception {
        try (var opened = new StreamProbe(2, 2, START)) {
            collect(START, true);
            opened.await();
        }
        collect(START.plusSeconds(1), true);
        var before = snapshot();
        collect(START, false);
        collect(START.plusSeconds(1), false);
        when(clock.instant()).thenReturn(START.plusSeconds(60));
        when(source.observe()).thenReturn(sample(START.plusSeconds(2), false));
        scheduler.observe();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(notifications.count()).isEqualTo(2);
        verify(delivery, times(1)).enqueue(any());
        try (var recovered = new StreamProbe(2, 4, START.plusSeconds(61))) {
            collect(START.plusSeconds(61), false);
            recovered.await();
        }
        assertWatermark(START.plusSeconds(61), PaymentAnomalyPolicy.Status.NORMAL);
    }

    @Test
    void brokenStreamDoesNotRollBackCommitOrPreventOtherRecipientDelivery() throws Exception {
        NotificationStream broken = message -> { throw new IOException("disconnected"); };
        streams.register(7L, broken);
        var received = new CountDownLatch(1);
        NotificationStream healthy = message -> received.countDown();
        streams.register(8L, healthy);
        try {
            collect(START, true);
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(notifications.count()).isEqualTo(2);
            assertWatermark(START, PaymentAnomalyPolicy.Status.ACTIVE);
        } finally {
            streams.unregister(7L, broken);
            streams.unregister(8L, healthy);
        }
    }

    private void collect(Instant at, boolean active) {
        when(clock.instant()).thenReturn(at);
        doReturn(sample(at, active)).when(source).observe();
        scheduler.observe();
    }

    static PaymentAnomalyObservation sample(Instant at, boolean active) {
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

    private final class StreamProbe implements AutoCloseable {
        private final ConcurrentLinkedQueue<NotificationPush> messages = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        private final CountDownLatch delivered;
        private final NotificationStream stream;
        private final int expected;

        StreamProbe(int expected, long stored, Instant watermark) {
            this.expected = expected;
            delivered = new CountDownLatch(expected);
            stream = message -> {
                try {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(notifications.count()).isEqualTo(stored);
                    assertThat(notifications.findById(Long.parseLong(message.notificationId()))).isPresent();
                    assertThat(states.findAll()).hasSize(5).allSatisfy(row -> assertThat(row.getLastObservedAt()).isEqualTo(watermark));
                    messages.add(message);
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    delivered.countDown();
                }
            };
            streams.register(7L, stream);
            streams.register(8L, stream);
        }

        void await() throws InterruptedException {
            assertThat(delivered.await(5, TimeUnit.SECONDS)).as("committed SSE delivery").isTrue();
            assertThat(failures).isEmpty();
            assertThat(messages).hasSize(expected);
        }

        @Override
        public void close() {
            streams.unregister(7L, stream);
            streams.unregister(8L, stream);
        }
    }
}
