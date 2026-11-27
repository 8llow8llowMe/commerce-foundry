package com.commercefoundry.domainlayer.notification.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.notification.application.model.PaymentAnomalyObservationTiming;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertStatePort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicySettings;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentAnomalyAlertTimingTest {
    private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");
    private final PaymentAnomalyAlertStatePort states = mock(PaymentAnomalyAlertStatePort.class);
    private final NotificationBatchInsertPort notifications = mock(NotificationBatchInsertPort.class);
    private final Clock clock = mock(Clock.class);
    private final AtomicLong ticker = new AtomicLong();
    private final PaymentAnomalyAlertStateProcessor processor = new PaymentAnomalyAlertStateProcessor(states, notifications,
        mock(SnowflakeIdGenerator.class), clock, ticker::get);

    @Test
    void exactBudgetNegativeStartAndNanoOverflowAreAccepted() {
        for (long[] times : List.of(new long[] {0, 30_000_000_000L}, new long[] {-100, -50}, new long[] {Long.MAX_VALUE - 20, Long.MIN_VALUE + 29})) {
            reset(states, clock, notifications);
            when(states.findLocked()).thenReturn(Optional.empty());
            when(clock.instant()).thenReturn(NOW);
            ticker.set(times[1]);
            accept(new PaymentAnomalyObservationTiming(NOW, NOW, times[0]));
            verify(states).insertInitial(any());
            verifyNoInteractions(notifications);
        }
    }

    @Test
    void elapsedOverBudgetNegativeElapsedAndWallReversalsDoNotWrite() {
        for (var timing : List.of(new PaymentAnomalyObservationTiming(NOW, NOW, -30_000_000_001L),
            new PaymentAnomalyObservationTiming(NOW, NOW, 1), new PaymentAnomalyObservationTiming(NOW, NOW.minusNanos(1), 0),
            new PaymentAnomalyObservationTiming(NOW, NOW.plusNanos(1), 0))) {
            reset(states, clock, notifications);
            when(states.findLocked()).thenReturn(Optional.empty());
            when(clock.instant()).thenReturn(NOW);
            ticker.set(0);
            assertThat(accept(timing)).isEmpty();
            verify(states).findLocked();
            verifyNoMoreInteractions(states);
            verifyNoInteractions(notifications);
        }
    }

    @Test
    void finalClockAndTickerAreReadAfterLockAndCannotBeReplacedByEarlierRead() {
        for (boolean clockReversal : List.of(false, true)) {
            reset(states, clock);
            ticker.set(0);
            when(states.findLocked()).thenAnswer(call -> {
                doReturn(clockReversal ? NOW.minusNanos(1) : NOW).when(clock).instant();
                ticker.set(clockReversal ? 0 : 30_000_000_001L);
                return Optional.empty();
            });
            when(clock.instant()).thenThrow(new AssertionError("clock read before lock"));
            assertThat(accept(new PaymentAnomalyObservationTiming(NOW, NOW, 0))).isEmpty();
            var order = inOrder(states, clock);
            order.verify(states).findLocked();
            order.verify(clock).instant();
            verifyNoMoreInteractions(states);
        }
    }

    @Test
    void timingAndBothWallInstantsAreRequired() {
        assertThatThrownBy(() -> accept(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentAnomalyObservationTiming(null, NOW, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentAnomalyObservationTiming(NOW, null, 0)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(states, notifications, clock);
    }

    @Test
    void sixSignalsTimesThousandRecipientsAreSavedInBatchLimitChunksInsideOneCall() {
        var chunked = new PaymentAnomalyAlertStateProcessor(states, notifications, new SnowflakeIdGenerator(1, 1), clock, ticker::get);
        when(states.findLocked()).thenReturn(Optional.empty());
        when(clock.instant()).thenReturn(NOW);
        ticker.set(0);
        when(notifications.insertAll(any())).thenAnswer(call -> List.copyOf(call.<List<Notification>>getArgument(0)));
        var active = new PaymentAnomalyObservation.Fact(1, NOW.minusSeconds(3600));
        var recipients = LongStream.rangeClosed(1, 1000).boxed().toList();
        var saved = chunked.accept(new PaymentAnomalyObservation(NOW, active, active, active, active, active, active, active), recipients,
            PaymentAnomalyPolicySettings.defaults(), new PaymentAnomalyObservationTiming(NOW, NOW, 0));
        assertThat(saved).hasSize(6000);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> batches = ArgumentCaptor.forClass(List.class);
        verify(notifications, times(2)).insertAll(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(PaymentAnomalyAlertStateProcessor.MAX_INSERT_BATCH, 1000);
        assertThat(batches.getAllValues().stream().flatMap(List::stream).toList()).containsExactlyElementsOf(saved);
        // 사건 이름순으로 저장한다 — 첫 1,000건이 자동 취소 수동 확인 개시 알림이다
        assertThat(saved.subList(0, 1000)).allSatisfy(n -> {
            assertThat(n.title()).isEqualTo("결제 자동 취소 수동 확인 후보가 감지되었습니다. 담당자는 점검해 주세요.");
            assertThat(n.severity()).isEqualTo(NotificationSeverity.CRITICAL);
            assertThat(n.linkPath()).isEqualTo("/admin/payments");
            assertThat(n.sourceType()).isEqualTo("PAYMENT_ANOMALY_ALERT");
        });
        verify(states).insertInitial(any());
    }

    @Test
    void autoCancelTitlesForRepeatAndDisappearanceDoNotClaimCompletion() {
        var titled = new PaymentAnomalyAlertStateProcessor(states, notifications, new SnowflakeIdGenerator(1, 1), clock, ticker::get);
        when(notifications.insertAll(any())).thenAnswer(call -> List.copyOf(call.<List<Notification>>getArgument(0)));
        var zero = new PaymentAnomalyObservation.Fact(0, null);
        var opened = PaymentAnomalyPolicy.accept(null, new PaymentAnomalyObservation(NOW, zero, zero, zero, zero, zero, zero,
            new PaymentAnomalyObservation.Fact(1, NOW)), NOW, PaymentAnomalyPolicySettings.defaults()).state();
        when(states.findLocked()).thenReturn(Optional.of(opened));
        var repeatedAt = NOW.plusSeconds(3600);
        when(clock.instant()).thenReturn(repeatedAt);
        ticker.set(0);
        var repeated = titled.accept(new PaymentAnomalyObservation(repeatedAt, zero, zero, zero, zero, zero, zero, new PaymentAnomalyObservation.Fact(1, NOW)),
            List.of(1L), PaymentAnomalyPolicySettings.defaults(), new PaymentAnomalyObservationTiming(repeatedAt, repeatedAt, 0));
        assertThat(repeated).singleElement().satisfies(n -> {
            assertThat(n.title()).isEqualTo("결제 자동 취소 수동 확인 후보가 지속되고 있습니다. 담당자는 점검해 주세요.");
            assertThat(n.severity()).isEqualTo(NotificationSeverity.CRITICAL);
        });
        var endedAt = repeatedAt.plusSeconds(1);
        when(clock.instant()).thenReturn(endedAt);
        var ended = titled.accept(new PaymentAnomalyObservation(endedAt, zero, zero, zero, zero, zero, zero, zero),
            List.of(1L), PaymentAnomalyPolicySettings.defaults(), new PaymentAnomalyObservationTiming(endedAt, endedAt, 0));
        assertThat(ended).singleElement().satisfies(n -> {
            assertThat(n.title()).isEqualTo("결제 자동 취소 수동 확인 후보가 관측되지 않습니다. 업무 처리 결과는 별도로 확인해 주세요.");
            assertThat(n.title()).doesNotContain("완료");
            assertThat(n.severity()).isEqualTo(NotificationSeverity.INFO);
        });
    }

    private List<?> accept(PaymentAnomalyObservationTiming timing) {
        var zero = new PaymentAnomalyObservation.Fact(0, null);
        return processor.accept(new PaymentAnomalyObservation(NOW, zero, zero, zero, zero, zero, zero, zero), List.of(1L),
            PaymentAnomalyPolicySettings.defaults(), timing);
    }
}
