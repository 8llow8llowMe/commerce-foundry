package com.commercefoundry.domainlayer.payment.domain.model;

import java.time.Instant;

/** 결제 소유의 단일 스냅샷 관측값. manualReviewUnknown은 unknown의 부분집합이다. */
public record PaymentAnomalyObservation(
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
