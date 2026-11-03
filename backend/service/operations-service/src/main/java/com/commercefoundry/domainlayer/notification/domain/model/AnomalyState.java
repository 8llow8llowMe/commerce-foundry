package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Duration;
import java.time.Instant;

/** topic별 rolling window. 전달 중복은 별도 record identity로 판단한다. */
public record AnomalyState(long id, String sourceKey, Instant lastSeenAt, Instant lastNotifiedAt, Long version) {
    public boolean shouldNotify(Instant now, Duration window) {
        return lastNotifiedAt == null || !now.isBefore(lastNotifiedAt.plus(window));
    }

    public AnomalyState observe(Instant now, boolean notify) {
        return new AnomalyState(id, sourceKey, now, notify ? now : lastNotifiedAt, version);
    }
}
