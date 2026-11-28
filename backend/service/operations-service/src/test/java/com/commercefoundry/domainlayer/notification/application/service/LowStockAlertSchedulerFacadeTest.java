package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockAlertLockPort;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockObservationPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockObservationProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertPushProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.global.properties.LowStockAlertProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 실제 원천 순회 Processor 와 Facade 의 순서 · 실패 경계(#229). 실제 DB 커밋 · SSE 는 LowStockAlertFlowTest 의 책임이다. */
class LowStockAlertSchedulerFacadeTest {
    private static final Instant OBSERVED = Instant.parse("2026-11-28T01:00:00Z");
    private static final long STARTED = -123L;
    private final LowStockObservationPort source = mock(LowStockObservationPort.class);
    private final NotificationRecipientPort recipients = mock(NotificationRecipientPort.class);
    private final LowStockAlertLockPort lock = mock(LowStockAlertLockPort.class);
    private final LowStockAlertStateProcessor states = mock(LowStockAlertStateProcessor.class);
    private final PaymentAnomalyAlertPushProcessor push = mock(PaymentAnomalyAlertPushProcessor.class);
    private final LongSupplier ticker = mock(LongSupplier.class);
    private final LowStockAlertProperties properties = properties(true, 3);
    private final LowStockObservationProcessor observations = new LowStockObservationProcessor(source, recipients, lock);
    private final LowStockAlertSchedulerFacade facade = new LowStockAlertSchedulerFacade(observations, states, push, properties, ticker);

    @BeforeEach
    void setup() {
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).when(lock).runLocked(any());
        when(ticker.getAsLong()).thenReturn(STARTED);
        when(recipients.findAllActiveAdminIds()).thenReturn(List.of(7L, 8L));
    }

    @Test
    void disabledDoesNotAcquireLockOrReadAnything() {
        new LowStockAlertSchedulerFacade(observations, states, push, properties(false, 3), ticker).observe();
        verifyNoInteractions(lock, recipients, source, states, push, ticker);
    }

    @Test
    void unavailableLockIsSwallowedWithoutCollecting() {
        doThrow(new IllegalStateException("lock unavailable")).when(lock).runLocked(any());
        assertThatCode(facade::observe).doesNotThrowAnyException();
        verifyNoInteractions(recipients, source, states, push, ticker);
    }

    @Test
    void lockNotAcquiredDoesNotCollect() {
        doNothing().when(lock).runLocked(any());
        facade.observe();
        verifyNoInteractions(recipients, source, states, push, ticker);
    }

    @Test
    void recipientFailureStopsBeforeSource() {
        when(recipients.findAllActiveAdminIds()).thenThrow(new NotificationException(NotificationErrorCode.RECIPIENTS_UNAVAILABLE));
        assertThatCode(facade::observe).doesNotThrowAnyException();
        verifyNoInteractions(source, states, push);
    }

    @Test
    void noRecipientNeverReadsSourceOrCallsStorage() {
        when(recipients.findAllActiveAdminIds()).thenReturn(List.of());
        facade.observe();
        verifyNoInteractions(source, states, push);
    }

    @Test
    void recipientsOverLimitNeverReadSource() {
        when(recipients.findAllActiveAdminIds()).thenReturn(LongStream.rangeClosed(1, LowStockAlertPolicy.MAX_RECIPIENTS + 1).boxed().toList());
        facade.observe();
        verifyNoInteractions(source, states, push);
    }

    @Test
    void completeScanFollowsCursorThenStoresOutsideRemoteCallsAndDeliversSavedNotifications() {
        when(source.observe(0L, 100)).thenReturn(page(0, true, 3L, 5L));
        when(source.observe(5L, 100)).thenReturn(page(5, false, 9L));
        var saved = List.of(notification(1L, 7L), notification(2L, 8L));
        when(states.accept(any(), any(), any(), anyLong())).thenReturn(saved);

        facade.observe();

        var order = inOrder(lock, ticker, recipients, source, states, push);
        order.verify(lock).runLocked(any());
        order.verify(ticker).getAsLong();
        order.verify(recipients).findAllActiveAdminIds();
        order.verify(source).observe(0L, 100);
        order.verify(source).observe(5L, 100);
        var scan = ArgumentCaptor.forClass(LowStockAlertPolicy.Scan.class);
        order.verify(states).accept(scan.capture(), eq(List.of(7L, 8L)), eq(properties.settings()), eq(STARTED));
        order.verify(push).enqueue(saved);
        assertThat(scan.getValue().skuIds()).containsExactly(3L, 5L, 9L);
        assertThat(scan.getValue().complete()).isTrue();
        assertThat(scan.getValue().observedAt()).isEqualTo(OBSERVED);
        verifyNoMoreInteractions(source, states, push);
        verify(ticker, times(1)).getAsLong();
    }

    @Test
    void maxPagesStopsReadingAndHandsTruncatedScanToStorage() {
        when(source.observe(0L, 100)).thenReturn(page(0, true, 1L));
        when(source.observe(1L, 100)).thenReturn(page(1, true, 2L));
        when(source.observe(2L, 100)).thenReturn(page(2, true, 3L));
        when(states.accept(any(), any(), any(), anyLong())).thenReturn(List.of());

        facade.observe();

        verify(source, times(3)).observe(anyLong(), eq(100));
        verify(source, never()).observe(eq(3L), anyInt());
        var scan = ArgumentCaptor.forClass(LowStockAlertPolicy.Scan.class);
        verify(states).accept(scan.capture(), any(), any(), anyLong());
        assertThat(scan.getValue().complete()).isFalse();
        assertThat(scan.getValue().skuIds()).containsExactly(1L, 2L, 3L);
        verifyNoInteractions(push);
    }

    @Test
    void laterPageFailureDiscardsWholeScanWithoutRetry() {
        when(source.observe(0L, 100)).thenReturn(page(0, true, 3L));
        when(source.observe(3L, 100)).thenThrow(new NotificationException(NotificationErrorCode.LOW_STOCK_OBSERVATION_UNAVAILABLE));

        assertThatCode(facade::observe).doesNotThrowAnyException();

        verify(source, times(1)).observe(0L, 100);
        verify(source, times(1)).observe(3L, 100);
        verifyNoMoreInteractions(source);
        verifyNoInteractions(states, push);
    }

    @Test
    void cursorContractViolationDiscardsScan() {
        // 다음 커서(3)가 아닌 커서로 만든 페이지 — 건너뜀으로 보고 점검 전체를 버린다.
        when(source.observe(0L, 100)).thenReturn(page(0, true, 3L));
        when(source.observe(3L, 100)).thenReturn(page(4, false, 7L));

        facade.observe();

        verifyNoInteractions(states, push);
    }

    @Test
    void storageFailureDoesNotDeliver() {
        when(source.observe(0L, 100)).thenReturn(page(0, false, 3L));
        when(states.accept(any(), any(), any(), anyLong())).thenThrow(new IllegalStateException("could not obtain lock"));

        assertThatCode(facade::observe).doesNotThrowAnyException();

        verifyNoInteractions(push);
    }

    @Test
    void emptySaveResultDoesNotDeliver() {
        when(source.observe(0L, 100)).thenReturn(page(0, false));
        when(states.accept(any(), any(), any(), anyLong())).thenReturn(List.of());

        facade.observe();

        verify(states).accept(any(), eq(List.of(7L, 8L)), any(), eq(STARTED));
        verifyNoInteractions(push);
    }

    @Test
    void deliveryFailureIsSwallowedAfterCommit() {
        when(source.observe(0L, 100)).thenReturn(page(0, false, 3L));
        var saved = List.of(notification(1L, 7L));
        when(states.accept(any(), any(), any(), anyLong())).thenReturn(saved);
        doThrow(new IllegalArgumentException("queue full")).when(push).enqueue(any());

        assertThatCode(facade::observe).doesNotThrowAnyException();

        verify(push).enqueue(saved);
    }

    private static LowStockAlertPolicy.Page page(long after, boolean hasNext, Long... skuIds) {
        var ids = new ArrayList<Long>(List.of(skuIds));
        return new LowStockAlertPolicy.Page(after, OBSERVED.plusSeconds(after), ids, hasNext, hasNext ? ids.getLast() : null);
    }

    private static Notification notification(long id, long recipient) {
        return Notification.create(id, new NotificationRequest(NotificationKind.LOW_STOCK, NotificationSeverity.WARNING, "품절 임박", "/admin/stocks",
            recipient, "LOW_STOCK_ALERT", id));
    }

    private static LowStockAlertProperties properties(boolean enabled, int maxPages) {
        return new LowStockAlertProperties(enabled, null, null, maxPages, null, null, Duration.ofMinutes(5));
    }
}
