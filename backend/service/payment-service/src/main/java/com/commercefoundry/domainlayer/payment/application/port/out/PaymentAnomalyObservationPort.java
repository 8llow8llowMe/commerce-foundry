package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import java.time.Instant;

public interface PaymentAnomalyObservationPort {
    /** payment 한 문장으로 여섯 버킷을 읽는다. autoCancelManualReview는 채우지 않는다(null). */
    PaymentAnomalyObservation observeAnomalies(Instant observedAt);

    /** payment_auto_cancel의 MANUAL_REVIEW 후보. 자동 취소 게이트가 켜졌을 때만 호출한다(꺼진 DB에는 테이블이 없을 수 있다). */
    PaymentAnomalyObservation.Bucket observeAutoCancelManualReview(Instant observedAt);
}
