package com.commercefoundry.domainlayer.notification.application.port.out.query;

import java.time.Instant;

public record PaymentOutboxObservationQueryResult(Instant observedAt, long readyUnpublishedCount, Instant oldestReadyAvailableAt) {
    public PaymentOutboxObservationQueryResult {
        if (observedAt == null || readyUnpublishedCount < 0
            || (readyUnpublishedCount == 0) != (oldestReadyAvailableAt == null)
            || oldestReadyAvailableAt != null && oldestReadyAvailableAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("Invalid outbox observation");
        }
    }
}
