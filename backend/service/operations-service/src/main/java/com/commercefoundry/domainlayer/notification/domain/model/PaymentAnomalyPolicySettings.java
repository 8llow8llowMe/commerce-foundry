package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Duration;

/** Immutable policy values; activation and property binding belong to the later collector. */
public record PaymentAnomalyPolicySettings(Duration unknown, Duration manualReviewUnknown, Duration inProgress, Duration returnedReady,
                                           Duration lateApproved, Duration doneOrderUnsynced, Duration suppression, Duration maxObservationAge) {
    public PaymentAnomalyPolicySettings {
        bounded(unknown, Duration.ofSeconds(1), Duration.ofDays(1));
        bounded(manualReviewUnknown, Duration.ZERO, Duration.ofDays(1));
        bounded(inProgress, Duration.ofSeconds(1), Duration.ofDays(1));
        bounded(returnedReady, Duration.ofSeconds(1), Duration.ofDays(1));
        bounded(lateApproved, Duration.ZERO, Duration.ofDays(1));
        bounded(doneOrderUnsynced, Duration.ofSeconds(1), Duration.ofDays(1));
        bounded(suppression, Duration.ofSeconds(1), Duration.ofDays(7));
        bounded(maxObservationAge, Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    public static PaymentAnomalyPolicySettings defaults() {
        return new PaymentAnomalyPolicySettings(Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ZERO, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(30));
    }

    private static void bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid anomaly policy duration");
        }
    }
}
