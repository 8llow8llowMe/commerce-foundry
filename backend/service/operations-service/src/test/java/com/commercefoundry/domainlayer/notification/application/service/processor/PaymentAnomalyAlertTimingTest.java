package com.commercefoundry.domainlayer.notification.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.notification.application.model.PaymentAnomalyObservationTiming;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertStatePort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicySettings;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

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

    private List<?> accept(PaymentAnomalyObservationTiming timing) {
        var zero = new PaymentAnomalyObservation.Fact(0, null);
        return processor.accept(new PaymentAnomalyObservation(NOW, zero, zero, zero, zero, zero, zero), List.of(1L),
            PaymentAnomalyPolicySettings.defaults(), timing);
    }
}
