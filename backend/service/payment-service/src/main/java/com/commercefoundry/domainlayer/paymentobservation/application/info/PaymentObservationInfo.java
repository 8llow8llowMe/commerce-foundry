package com.commercefoundry.domainlayer.paymentobservation.application.info;

import java.time.Instant;

/** autoCancelManualReview 는 자동 취소 게이트가 꺼져 관측하지 않았으면 null 이다(0건과 구분). */
public record PaymentObservationInfo(
    Instant observedAt,
    Bucket unknown,
    Bucket manualReviewUnknown,
    Bucket inProgress,
    Bucket returnedReady,
    Bucket lateApproved,
    Bucket doneOrderUnsynced,
    Bucket autoCancelManualReview
) {
    public record Bucket(long count, Instant oldestAt) { }
}
