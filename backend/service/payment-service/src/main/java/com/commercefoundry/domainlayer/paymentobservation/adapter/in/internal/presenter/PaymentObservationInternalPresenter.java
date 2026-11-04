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
            toBucket(info.doneOrderUnsynced()));
    }

    private PaymentObservationResponse.Bucket toBucket(PaymentObservationInfo.Bucket bucket) {
        return new PaymentObservationResponse.Bucket(bucket.count(), bucket.oldestAt());
    }
}
