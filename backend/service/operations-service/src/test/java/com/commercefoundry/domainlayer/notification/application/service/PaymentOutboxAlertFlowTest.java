package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.PaymentOutboxAlertScheduler;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.OutboxAlertStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.*;
import com.commercefoundry.domainlayer.notification.application.port.out.query.PaymentOutboxObservationQueryResult;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentOutboxAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.*;
import com.commercefoundry.domainlayer.notification.application.info.PaymentOutboxObservationInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-alert;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.notifications.payment-outbox-alert.enabled=true", "commerce.messaging.enabled=false", "operations.jobs.worker.enabled=false",
    "operations.jobs.planner.enabled=false", "operations.notifications.payment-outbox-alert.initial-delay=1d"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PaymentOutboxAlertFlowTest {
    @Autowired PaymentOutboxAlertScheduler scheduler;
    @Autowired PaymentOutboxAlertStateProcessor processor;
    @Autowired OutboxAlertStateRepository states;
    @Autowired NotificationRepository notifications;
    @MockitoBean NotificationRecipientPort recipients;
    @MockitoBean PaymentOutboxObservationPort observations;
    @MockitoBean PaymentOutboxAlertLockPort lock;
    @MockitoBean Clock clock;
    @MockitoBean(name = "paymentOutboxAlertTicker") LongSupplier ticker;
    @MockitoSpyBean NotificationRepositoryPort notificationPort;
    @MockitoBean NotificationPushPort push;
    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");

    @BeforeEach
    void resetData() {
        states.deleteAll();
        notifications.deleteAll();
        when(clock.instant()).thenReturn(start);
        when(ticker.getAsLong()).thenReturn(0L);
        when(recipients.findAllActiveAdminIds()).thenReturn(List.of(7L, 8L));
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(lock).runLocked(any());
    }
    private void sample(Instant at, long count) {
        when(clock.instant()).thenReturn(at);
        doReturn(new PaymentOutboxObservationQueryResult(at, count, count == 0 ? null : start.minusSeconds(300))).when(observations).observe();
        scheduler.observe();
    }
    @Test
    void stalledSuppressedUnavailableRecoveryAndReplay(CapturedOutput output) {
        sample(start, 1);
        sample(start.plusSeconds(60), 1);
        when(observations.observe()).thenThrow(new IllegalStateException("SENTINEL_PRIVATE_TOKEN_URL"));
        scheduler.observe();
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(states.findById("PAYMENT").orElseThrow().getLastObservedAt()).isEqualTo(start.plusSeconds(60));
        sample(start.plusSeconds(120), 0);
        sample(start.plusSeconds(120), 0);
        sample(start.plusSeconds(180), 0);
        assertThat(notifications.findAll()).hasSize(4).filteredOn(n -> n.getSeverity() == NotificationSeverity.INFO).hasSize(2);
        assertThat(states.findById("PAYMENT").orElseThrow().getStatus()).isEqualTo(OutboxAlertState.Status.NORMAL);
        verify(push, timeout(3000).times(4)).push(any());
        assertThat(output.getAll()).doesNotContain("SENTINEL_PRIVATE_TOKEN_URL");
    }
    @Test
    void firstNormalWatermarkPersistsAndInvalidFirstSampleDoesNot() {
        processor.accept(new PaymentOutboxObservationInfo(start.minusSeconds(31), 0, null), List.of(7L), start, start, 0);
        assertThat(states.count()).isZero();
        sample(start, 0);
        assertThat(states.findById("PAYMENT").orElseThrow().getLastObservedAt()).isEqualTo(start);
        assertThat(notifications.count()).isZero();
        processor.accept(new PaymentOutboxObservationInfo(start.minusSeconds(1), 1, start.minusSeconds(400)), List.of(7L), start, start, 0);
        assertThat(notifications.count()).isZero();
    }
    @Test
    void secondRecipientFailureRollsBackStateFanoutAndPush() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i -> {
            if (count.incrementAndGet() == 2) throw new IllegalStateException("Second insert failed");
            return i.callRealMethod();
        }).when(notificationPort).insert(any());
        assertThatThrownBy(() -> processor.accept(new PaymentOutboxObservationInfo(start, 1, start.minusSeconds(300)),
            List.of(7L, 8L), start, start, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(states.count()).isZero();
        assertThat(notifications.count()).isZero();
        verify(push, never()).push(any());
    }
    @Test
    void recipientsFailBeforeSourceAndClockRegressionOrElapsedDeadlineRejectsSample() {
        when(recipients.findAllActiveAdminIds()).thenThrow(new IllegalStateException("unavailable"));
        scheduler.observe();
        verifyNoInteractions(observations);
        var source = new PaymentOutboxObservationInfo(start, 1, start.minusSeconds(300));
        processor.accept(source, List.of(7L), start.plusSeconds(1), start, 0);
        when(ticker.getAsLong()).thenReturn(30_000_000_001L);
        processor.accept(source, List.of(7L), start, start, 0);
        assertThat(states.count()).isZero();
        assertThat(notifications.count()).isZero();
    }
    @Test
    void committedAsyncPushCanBlockWithoutBlockingNextCollection() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delivered = new java.util.concurrent.CountDownLatch(4);
        var visibleFromWorker = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(i -> {
            visibleFromWorker.set(notifications.count() >= 2);
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Push barrier timeout");
            delivered.countDown();
            return null;
        }).when(push).push(any());
        try {
            sample(start, 1);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(visibleFromWorker.get()).isTrue();
            sample(start.plusSeconds(60), 0);
            assertThat(notifications.count()).isEqualTo(4);
        } finally { release.countDown(); }
        assertThat(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
}
