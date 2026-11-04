package com.commercefoundry.domainlayer.paymentobservation.application.info;

import java.time.Instant;

public record PaymentObservationInfo(
    Instant observedAt,
    Bucket unknown,
    Bucket manualReviewUnknown,
    Bucket inProgress,
    Bucket returnedReady,
    Bucket lateApproved,
    Bucket doneOrderUnsynced
) {
    public record Bucket(long count, Instant oldestAt) { }
}
