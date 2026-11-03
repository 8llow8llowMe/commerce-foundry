package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DltAlertStateTest {
    @Test
    void exactSlidingBoundaryAndFirstNotification() {
        var now = Instant.parse("2026-11-03T00:00:00Z");
        var empty = new DltAlertState("topic", null);
        assertThat(empty.shouldNotify(now, Duration.ofHours(1))).isTrue();
        var notified = empty.notified(now);
        assertThat(notified.shouldNotify(now.plusSeconds(3599).plusNanos(999999999), Duration.ofHours(1))).isFalse();
        assertThat(notified.shouldNotify(now.plusSeconds(3600), Duration.ofHours(1))).isTrue();
        assertThat(notified.shouldNotify(now.minusSeconds(1), Duration.ofHours(1))).isFalse();
    }
}
