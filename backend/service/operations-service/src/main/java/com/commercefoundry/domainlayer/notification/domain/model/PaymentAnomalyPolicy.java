package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Pure whole-sample decision. No persistence, notification creation, or payment mutation. */
public final class PaymentAnomalyPolicy {
    private PaymentAnomalyPolicy() { }
    public enum Family { UNKNOWN, IN_PROGRESS, RETURNED_READY, LATE_APPROVED, DONE_ORDER_UNSYNCED }
    public enum Status { NORMAL, ACTIVE }
    public enum Signal { OPENED, ESCALATED, REPEATED, CANDIDATES_DISAPPEARED }

    public record Incident(Status status, NotificationSeverity severity, Instant firstDetectedAt, Instant lastAlertAt) {
        public Incident {
            Objects.requireNonNull(status);
            if (status == Status.NORMAL && (severity != null || firstDetectedAt != null || lastAlertAt != null)
                || status == Status.ACTIVE && (severity != NotificationSeverity.WARNING && severity != NotificationSeverity.CRITICAL
                || firstDetectedAt == null || lastAlertAt == null || firstDetectedAt.isAfter(lastAlertAt))) {
                throw new IllegalArgumentException("Invalid anomaly incident");
            }
        }
    }
    public record State(Instant lastObservedAt, Instant lastAcceptedLocalAt, Map<Family, Incident> incidents) {
        public State {
            Objects.requireNonNull(lastObservedAt);
            Objects.requireNonNull(lastAcceptedLocalAt);
            incidents = Map.copyOf(incidents);
            if (incidents.size() != Family.values().length || !PaymentAnomalyObservation.microsecond(lastObservedAt)
                || lastObservedAt.isAfter(lastAcceptedLocalAt)) {
                throw new IllegalArgumentException("Incomplete anomaly state");
            }
            incidents.forEach((family, incident) -> {
                if (incident.status() == Status.ACTIVE && (incident.firstDetectedAt().isAfter(lastObservedAt)
                    || incident.lastAlertAt().isAfter(lastAcceptedLocalAt)
                    || !PaymentAnomalyObservation.microsecond(incident.firstDetectedAt())
                    || (family == Family.IN_PROGRESS || family == Family.RETURNED_READY) && incident.severity() != NotificationSeverity.WARNING
                    || (family == Family.LATE_APPROVED || family == Family.DONE_ORDER_UNSYNCED) && incident.severity() != NotificationSeverity.CRITICAL)) {
                    throw new IllegalArgumentException("Invalid anomaly state chronology");
                }
            });
        }
    }
    public record Notice(Signal signal, NotificationSeverity severity) { }
    public record Decision(State state, Map<Family, Notice> notices) {
        public Decision { notices = Map.copyOf(notices); }
    }

    public static Decision accept(State previous, PaymentAnomalyObservation sample, Instant now, PaymentAnomalyPolicySettings settings) {
        Objects.requireNonNull(settings);
        if (sample == null || now == null || sample.observedAt().isAfter(now)
            || Duration.between(sample.observedAt(), now).compareTo(settings.maxObservationAge()) > 0
            || previous != null && (!sample.observedAt().isAfter(previous.lastObservedAt()) || now.isBefore(previous.lastAcceptedLocalAt()))) {
            return new Decision(previous, Map.of());
        }
        var next = new EnumMap<Family, Incident>(Family.class);
        var notices = new EnumMap<Family, Notice>(Family.class);
        for (var family : Family.values()) {
            var fact = fact(sample, family);
            var old = previous == null ? null : previous.incidents().get(family);
            boolean active = old != null && old.status() == Status.ACTIVE;
            boolean manual = family == Family.UNKNOWN && aged(sample.manualReviewUnknown(), sample.observedAt(), settings.manualReviewUnknown());
            boolean qualifies = manual || aged(fact, sample.observedAt(), threshold(settings, family));
            var severity = manual || family == Family.LATE_APPROVED || family == Family.DONE_ORDER_UNSYNCED
                ? NotificationSeverity.CRITICAL : NotificationSeverity.WARNING;
            Incident incident;
            if (active && fact.count() == 0) {
                incident = new Incident(Status.NORMAL, null, null, null);
                notices.put(family, new Notice(Signal.CANDIDATES_DISAPPEARED, NotificationSeverity.INFO));
            } else if (!active && qualifies) {
                incident = new Incident(Status.ACTIVE, severity, sample.observedAt(), now);
                notices.put(family, new Notice(Signal.OPENED, severity));
            } else if (active && manual && old.severity() != NotificationSeverity.CRITICAL) {
                incident = new Incident(Status.ACTIVE, NotificationSeverity.CRITICAL, old.firstDetectedAt(), now);
                notices.put(family, new Notice(Signal.ESCALATED, NotificationSeverity.CRITICAL));
            } else if (active && qualifies && Duration.between(old.lastAlertAt(), now).compareTo(settings.suppression()) >= 0) {
                incident = new Incident(Status.ACTIVE, old.severity(), old.firstDetectedAt(), now);
                notices.put(family, new Notice(Signal.REPEATED, old.severity()));
            } else {
                incident = active ? old : new Incident(Status.NORMAL, null, null, null);
            }
            next.put(family, incident);
        }
        return new Decision(new State(sample.observedAt(), now, next), notices);
    }

    private static boolean aged(PaymentAnomalyObservation.Fact fact, Instant observed, Duration threshold) {
        return fact.count() > 0 && Duration.between(fact.oldestAt(), observed).compareTo(threshold) >= 0;
    }
    private static PaymentAnomalyObservation.Fact fact(PaymentAnomalyObservation sample, Family family) {
        return switch (family) {
            case UNKNOWN -> sample.unknown();
            case IN_PROGRESS -> sample.inProgress();
            case RETURNED_READY -> sample.returnedReady();
            case LATE_APPROVED -> sample.lateApproved();
            case DONE_ORDER_UNSYNCED -> sample.doneOrderUnsynced();
        };
    }
    private static Duration threshold(PaymentAnomalyPolicySettings settings, Family family) {
        return switch (family) {
            case UNKNOWN -> settings.unknown();
            case IN_PROGRESS -> settings.inProgress();
            case RETURNED_READY -> settings.returnedReady();
            case LATE_APPROVED -> settings.lateApproved();
            case DONE_ORDER_UNSYNCED -> settings.doneOrderUnsynced();
        };
    }
}
