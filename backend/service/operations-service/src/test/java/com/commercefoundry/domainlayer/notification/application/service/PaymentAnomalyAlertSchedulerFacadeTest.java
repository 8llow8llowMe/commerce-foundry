package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.out.client.NotificationRecipientAdapter;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.NotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.NotificationRecipientInternalResponseSupport;
import com.commercefoundry.domainlayer.notification.application.model.PaymentAnomalyObservationTiming;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertLockPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertPushProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.global.properties.PaymentAnomalyAlertProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionSystemException;

/** 실제 수신자 페이지 검증과 Facade의 실패 경계. 실제 DB 커밋은 별도 통합 시험의 책임이다. */
class PaymentAnomalyAlertSchedulerFacadeTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private final NotificationRecipientClient recipients = mock(NotificationRecipientClient.class);
    private final PaymentAnomalyObservationPort source = mock(PaymentAnomalyObservationPort.class);
    private final PaymentAnomalyAlertLockPort lock = mock(PaymentAnomalyAlertLockPort.class);
    private final PaymentAnomalyAlertStateProcessor states = mock(PaymentAnomalyAlertStateProcessor.class);
    private final PaymentAnomalyAlertPushProcessor push = mock(PaymentAnomalyAlertPushProcessor.class);
    private final Clock clock = mock(Clock.class);
    private final LongSupplier ticker = mock(LongSupplier.class);
    private final PaymentAnomalyAlertProperties properties = properties(true);
    private final PaymentAnomalyObservation sample = sample();
    private final PaymentAnomalyObservationProcessor observations = new PaymentAnomalyObservationProcessor(source,
        new NotificationRecipientAdapter(recipients, new NotificationRecipientInternalResponseSupport(CircuitBreakerRegistry.ofDefaults())), lock);
    private final PaymentAnomalyAlertSchedulerFacade facade = new PaymentAnomalyAlertSchedulerFacade(observations, states, push, properties, clock, ticker);

    @BeforeEach
    void setup() {
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).when(lock).runLocked(any());
        when(clock.instant()).thenReturn(NOW);
        when(ticker.getAsLong()).thenReturn(-123L);
    }

    @Test
    void disabledDoesNotAcquireLockOrReadAnything() {
        new PaymentAnomalyAlertSchedulerFacade(observations, states, push, properties(false), clock, ticker).observe();
        verifyNoInteractions(lock, recipients, source, states, push, clock, ticker);
    }

    @Test
    void unavailableLockDoesNotCollect() {
        doThrow(new IllegalStateException("lock unavailable")).when(lock).runLocked(any());
        assertThatCode(facade::observe).doesNotThrowAnyException();
        verifyNoInteractions(recipients, source, states, push, clock, ticker);
    }

    @Test
    void lockNotAcquiredDoesNotCollect() {
        doNothing().when(lock).runLocked(any());
        facade.observe();
        verifyNoInteractions(recipients, source, states, push, clock, ticker);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1001})
    void invalidRecipientCountDoesNotCallSourceOrState(int count) {
        recipientPages(count);
        assertThatCode(facade::observe).doesNotThrowAnyException();
        verify(recipients, times(count == 0 ? 1 : 11)).page(any(), eq(100));
        verifyNoInteractions(source, states, push, clock, ticker);
    }

    @Test
    void thousandRecipientsRequireEleventhEmptyPageBeforeSourceAndState() {
        recipientPages(1000);
        when(source.observe()).thenAnswer(call -> {
            verify(recipients).page(1000L, 100);
            verifyNoInteractions(states, push);
            return sample;
        });
        when(states.accept(any(), any(), any(), any())).thenReturn(List.of());
        facade.observe();
        var order = inOrder(recipients, source, states);
        order.verify(recipients).page(null, 100);
        for (long after = 100; after <= 1000; after += 100) {
            order.verify(recipients).page(after, 100);
        }
        order.verify(source).observe();
        order.verify(states).accept(eq(sample), eq(LongStream.rangeClosed(1, 1000).boxed().toList()), eq(properties.settings()), any());
        verifyNoMoreInteractions(recipients, source, states);
        verifyNoInteractions(push);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOrIncompleteLaterRecipientPageDoesNotUsePartialRecipients(boolean throwsException) {
        recipientPages(100);
        if (throwsException) {
            when(recipients.page(100L, 100)).thenThrow(new IllegalStateException("auth unavailable"));
        } else {
            when(recipients.page(100L, 100)).thenReturn(Response.success(null));
        }
        assertThatCode(facade::observe).doesNotThrowAnyException();
        verify(recipients).page(null, 100);
        verify(recipients).page(100L, 100);
        verifyNoInteractions(source, states, push, clock, ticker);
    }

    @Test
    void sourceFailureDoesNotCallStateOrPush() {
        recipientPages(1);
        when(source.observe()).thenThrow(new IllegalStateException("source unavailable"));
        assertThatCode(facade::observe).doesNotThrowAnyException();
        verify(source).observe();
        verifyNoInteractions(states, push);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageOrCommitReturnFailureDoesNotEnqueueOrRetry(boolean commitFailure) {
        successfulObservation();
        RuntimeException failure = commitFailure ? new TransactionSystemException("commit unavailable") : new IllegalStateException("storage unavailable");
        when(states.accept(any(), any(), any(), any())).thenThrow(failure);
        assertThatCode(facade::observe).doesNotThrowAnyException();
        verify(states).accept(eq(sample), eq(List.of(1L)), eq(properties.settings()), any());
        verifyNoMoreInteractions(states);
        verifyNoInteractions(push);
    }

    @Test
    void emptyStorageResultDoesNotEnqueue() {
        successfulObservation();
        when(states.accept(any(), any(), any(), any())).thenReturn(List.of());
        facade.observe();
        verify(states).accept(eq(sample), eq(List.of(1L)), eq(properties.settings()), any());
        verifyNoInteractions(push);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enqueuesReturnedBatchOnlyAfterStorageReturnsAndNeverRestoresOnPushFailure(boolean pushFailure) {
        successfulObservation();
        var saved = List.of(new Notification(11L, 1L, NotificationKind.ANOMALY, NotificationSeverity.CRITICAL,
            "결제 이상 후보", "/admin/payments", "PAYMENT_ANOMALY_ALERT", 11L, null, NOW, 0L));
        when(states.accept(any(), any(), any(), any())).thenAnswer(call -> {
            verifyNoInteractions(push);
            return saved;
        });
        if (pushFailure) {
            doThrow(new IllegalStateException("queue full")).when(push).enqueue(any());
        }
        assertThatCode(facade::observe).doesNotThrowAnyException();
        var order = inOrder(source, states, push);
        order.verify(source).observe();
        order.verify(states).accept(eq(sample), eq(List.of(1L)), eq(properties.settings()), any());
        order.verify(push).enqueue(same(saved));
        verifyNoMoreInteractions(source, states, push);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L, 30_000_000_000L, 30_000_000_001L})
    void capturesHttpTimingAndLeavesBoundaryValidationToStateProcessor(long wallOffsetNanos) {
        successfulObservation();
        when(clock.instant()).thenReturn(NOW, NOW.plusNanos(wallOffsetNanos));
        when(states.accept(any(), any(), any(), any())).thenReturn(List.of());
        facade.observe();
        var timing = ArgumentCaptor.forClass(PaymentAnomalyObservationTiming.class);
        var order = inOrder(recipients, clock, ticker, source, states);
        order.verify(recipients).page(null, 100);
        order.verify(clock).instant();
        order.verify(ticker).getAsLong();
        order.verify(source).observe();
        order.verify(clock).instant();
        order.verify(states).accept(eq(sample), eq(List.of(1L)), eq(properties.settings()), timing.capture());
        assertThat(timing.getValue()).isEqualTo(new PaymentAnomalyObservationTiming(NOW, NOW.plusNanos(wallOffsetNanos), -123L));
        verifyNoInteractions(push);
    }

    private void successfulObservation() {
        recipientPages(1);
        when(source.observe()).thenReturn(sample);
    }

    private void recipientPages(int total) {
        when(recipients.page(any(), eq(100))).thenAnswer(call -> {
            Long after = call.getArgument(0);
            long first = after == null ? 1 : after + 1;
            var ids = LongStream.range(first, Math.min(first + 100, total + 1L)).mapToObj(Long::toString).toList();
            return Response.success(new NotificationRecipientClient.RecipientPage(ids));
        });
    }

    private static PaymentAnomalyAlertProperties properties(boolean enabled) {
        return new PaymentAnomalyAlertProperties(enabled, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static PaymentAnomalyObservation sample() {
        var empty = new PaymentAnomalyObservation.Fact(0, null);
        return new PaymentAnomalyObservation(NOW, empty, empty, empty, empty, empty, empty);
    }
}
