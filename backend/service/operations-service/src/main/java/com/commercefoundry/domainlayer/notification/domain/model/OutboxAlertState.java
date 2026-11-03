package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Observation watermarks and incident decisions only; no payment business state. */
public record OutboxAlertState(Status status, Instant lastObservedAt, Instant lastAcceptedLocalAt, Instant firstDetectedAt, Instant lastAlertAt) {
    public enum Status { NORMAL, ACTIVE }
    public enum Signal { NONE, STALLED, RECOVERED }
    public record Decision(OutboxAlertState state, Signal signal) { }

    public OutboxAlertState {
        Objects.requireNonNull(status);
        Objects.requireNonNull(lastObservedAt);
        Objects.requireNonNull(lastAcceptedLocalAt);
        if (status == Status.NORMAL && (firstDetectedAt != null || lastAlertAt != null)
            || status == Status.ACTIVE && (firstDetectedAt == null || lastAlertAt == null)) {
            throw new IllegalArgumentException("Invalid incident state");
        }
    }

    public static Decision accept(OutboxAlertState previous, Instant observed, long count, Instant oldest,
                                 Instant now, Duration threshold, Duration suppression, Duration maxAge) {
        if (observed == null || now == null || count < 0 || (count == 0) != (oldest == null)
            || oldest != null && oldest.isAfter(observed) || observed.isAfter(now) || Duration.between(observed, now).compareTo(maxAge) > 0
            || previous != null && (!observed.isAfter(previous.lastObservedAt) || now.isBefore(previous.lastAcceptedLocalAt))) {
            return new Decision(previous, Signal.NONE);
        }
        boolean active = previous != null && previous.status == Status.ACTIVE;
        boolean stalled = count > 0 && Duration.between(oldest, observed).compareTo(threshold) >= 0;
        if (active && count == 0) {
            return new Decision(new OutboxAlertState(Status.NORMAL, observed, now, null, null), Signal.RECOVERED);
        }
        if (stalled && (!active || !now.isBefore(previous.lastAlertAt.plus(suppression)))) {
            return new Decision(new OutboxAlertState(Status.ACTIVE, observed, now, active ? previous.firstDetectedAt : observed, now), Signal.STALLED);
        }
        return new Decision(new OutboxAlertState(active ? Status.ACTIVE : Status.NORMAL, observed, now,
            active ? previous.firstDetectedAt : null, active ? previous.lastAlertAt : null), Signal.NONE);
    }
}
