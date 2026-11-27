package com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.dto.response.PaymentObservationResponse;
import com.commercefoundry.domainlayer.paymentobservation.application.info.PaymentObservationInfo;
import org.springframework.stereotype.Component;

@Component
public class PaymentObservationInternalPresenter {
    public PaymentObservationResponse toResponse(PaymentObservationInfo info) {
        return new PaymentObservationResponse(info.observedAt(),
            toBucket(info.unknown()),
            toBucket(info.manualReviewUnknown()),
            toBucket(info.inProgress()),
            toBucket(info.returnedReady()),
            toBucket(info.lateApproved()),
            toBucket(info.doneOrderUnsynced()),
            toBucket(info.autoCancelManualReview()));
    }

    private PaymentObservationResponse.Bucket toBucket(PaymentObservationInfo.Bucket bucket) {
        // 미관측(null) 버킷은 JSON null 로 그대로 내보낸다. {count:0, oldestAt:null} 과 의미가 다르다.
        return bucket == null ? null : new PaymentObservationResponse.Bucket(bucket.count(), bucket.oldestAt());
    }
}
