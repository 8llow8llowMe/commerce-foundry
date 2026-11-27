package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Aggregate facts only: ages measure request start / PG approval, not state residence.
 *
 * <p>{@code autoCancelManualReview} is nullable. null means "not observed" (payment auto-cancel gate OFF, or an older payment
 * without the field) and differs from {@code {0, null}}. It is an independent fact aged by the last scheduled/claimed attempt,
 * so it takes no part in the UNKNOWN subset relation; only its chronology is checked.
 */
public record PaymentAnomalyObservation(Instant observedAt, Fact unknown, Fact manualReviewUnknown, Fact inProgress,
                                        Fact returnedReady, Fact lateApproved, Fact doneOrderUnsynced, Fact autoCancelManualReview) {
    public record Fact(long count, Instant oldestAt) {
        public Fact {
            if (count < 0 || (count == 0) != (oldestAt == null) || oldestAt != null && !microsecond(oldestAt)) {
                throw new IllegalArgumentException("Invalid anomaly fact");
            }
        }
    }

    public PaymentAnomalyObservation {
        if (!microsecond(observedAt) || unknown == null || manualReviewUnknown == null || inProgress == null
            || returnedReady == null || lateApproved == null || doneOrderUnsynced == null) {
            throw new IllegalArgumentException("Invalid anomaly observation");
        }
        for (var fact : List.of(unknown, manualReviewUnknown, inProgress, returnedReady, lateApproved, doneOrderUnsynced)) {
            if (fact.oldestAt() != null && fact.oldestAt().isAfter(observedAt)) {
                throw new IllegalArgumentException("Invalid anomaly chronology");
            }
        }
        if (autoCancelManualReview != null && autoCancelManualReview.oldestAt() != null && autoCancelManualReview.oldestAt().isAfter(observedAt)) {
            throw new IllegalArgumentException("Invalid anomaly chronology");
        }
        if (manualReviewUnknown.count() > unknown.count() || manualReviewUnknown.count() > 0
            && (manualReviewUnknown.oldestAt().isBefore(unknown.oldestAt())
            || manualReviewUnknown.count() == unknown.count() && !manualReviewUnknown.oldestAt().equals(unknown.oldestAt()))) {
            throw new IllegalArgumentException("Invalid anomaly subset");
        }
    }

    static boolean microsecond(Instant value) {
        return value != null && value.getNano() % 1000 == 0;
    }
}
