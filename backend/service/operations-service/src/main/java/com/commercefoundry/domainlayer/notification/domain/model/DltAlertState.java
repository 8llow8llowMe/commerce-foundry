package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Duration;
import java.time.Instant;

/** DLT alert metadata only; see anomaly-notifications.md. */
public record DltAlertState(String topic, Instant lastNotifiedAt) {
    public boolean shouldNotify(Instant now, Duration suppression) {
        return lastNotifiedAt == null || !now.isBefore(lastNotifiedAt.plus(suppression));
    }

    public DltAlertState notified(Instant now) {
        return new DltAlertState(topic, now);
    }
}
