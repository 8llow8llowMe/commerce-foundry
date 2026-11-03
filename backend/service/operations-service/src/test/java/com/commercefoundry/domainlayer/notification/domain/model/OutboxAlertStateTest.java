package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxAlertStateTest {
    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");
    private OutboxAlertState.Decision accept(OutboxAlertState state, Instant observed, long count, Instant oldest, Instant now) {
        return OutboxAlertState.accept(state, observed, count, oldest, now, Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofSeconds(30));
    }
    @Test
    void thresholdSuppressionRecoveryAndNewIncident() {
        var below = accept(null, start, 1, start.minusSeconds(299), start);
        assertThat(below.signal()).isEqualTo(OutboxAlertState.Signal.NONE);
        var active = accept(below.state(), start.plusSeconds(1), 1, start.minusSeconds(299), start.plusSeconds(1));
        assertThat(active.signal()).isEqualTo(OutboxAlertState.Signal.STALLED);
        assertThat(active.state().firstDetectedAt()).isEqualTo(start.plusSeconds(1));
        var suppressed = accept(active.state(), start.plusSeconds(3600), 1, start, start.plusSeconds(3600));
        assertThat(suppressed.signal()).isEqualTo(OutboxAlertState.Signal.NONE);
        assertThat(suppressed.state().lastAlertAt()).isEqualTo(start.plusSeconds(1));
        var repeat = accept(suppressed.state(), start.plusSeconds(3601), 1, start, start.plusSeconds(3601));
        assertThat(repeat.signal()).isEqualTo(OutboxAlertState.Signal.STALLED);
        var younger = accept(repeat.state(), start.plusSeconds(3602), 1, start.plusSeconds(3600), start.plusSeconds(3602));
        assertThat(younger.state().status()).isEqualTo(OutboxAlertState.Status.ACTIVE);
        assertThat(younger.signal()).isEqualTo(OutboxAlertState.Signal.NONE);
        var recovery = accept(younger.state(), start.plusSeconds(3603), 0, null, start.plusSeconds(3603));
        assertThat(recovery.signal()).isEqualTo(OutboxAlertState.Signal.RECOVERED);
        assertThat(recovery.state().firstDetectedAt()).isNull();
        assertThat(accept(recovery.state(), start.plusSeconds(3604), 0, null, start.plusSeconds(3604)).signal()).isEqualTo(OutboxAlertState.Signal.NONE);
        assertThat(accept(recovery.state(), start.plusSeconds(3604), 1, start, start.plusSeconds(3604)).signal()).isEqualTo(OutboxAlertState.Signal.STALLED);
    }
    @Test
    void invalidObservationsDoNotChangeWatermarks() {
        var state = accept(null, start, 0, null, start).state();
        assertThat(accept(state, start, 0, null, start).state()).isSameAs(state);
        assertThat(accept(state, start.minusSeconds(1), 0, null, start).state()).isSameAs(state);
        assertThat(accept(state, start.plusSeconds(1), 0, null, start).state()).isSameAs(state);
        assertThat(accept(state, start.plusSeconds(1), 0, null, start.plusSeconds(32)).state()).isSameAs(state);
        assertThat(accept(state, start.plusSeconds(1), 0, null, start.minusSeconds(1)).state()).isSameAs(state);
        assertThat(accept(null, start, 0, null, start.plusSeconds(31)).state()).isNull();
    }
}
