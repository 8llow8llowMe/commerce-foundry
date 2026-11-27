package com.commercefoundry.domainlayer.payment.domain.model;

import java.time.Instant;

/**
 * 결제 소유의 관측값. 앞 여섯 버킷은 payment 한 문장의 스냅샷이고 manualReviewUnknown은 unknown의 부분집합이다.
 * autoCancelManualReview는 같은 트랜잭션의 두 번째 문장(payment_auto_cancel)이라 앞 여섯과 다른 스냅샷일 수 있고,
 * 자동 취소 게이트가 꺼져 있으면 관측하지 않았다는 뜻으로 null이다(0건과 구분한다).
 */
public record PaymentAnomalyObservation(
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

    public PaymentAnomalyObservation withAutoCancelManualReview(Bucket bucket) {
        return new PaymentAnomalyObservation(observedAt, unknown, manualReviewUnknown, inProgress, returnedReady, lateApproved,
            doneOrderUnsynced, bucket);
    }
}
