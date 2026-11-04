package com.commercefoundry.domainlayer.paymentobservation.application.service;

import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.dto.response.PaymentObservationResponse;
import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.presenter.PaymentObservationInternalPresenter;
import com.commercefoundry.domainlayer.paymentobservation.application.exception.PaymentObservationErrorCode;
import com.commercefoundry.domainlayer.paymentobservation.application.exception.PaymentObservationException;
import com.commercefoundry.domainlayer.paymentobservation.application.info.PaymentObservationInfo;
import com.commercefoundry.domainlayer.paymentobservation.application.port.in.PaymentObservationInternalUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentObservationInternalFacade implements PaymentObservationInternalUseCase {
    private final PaymentAnomalyObservationProcessor processor;
    private final PaymentObservationInternalPresenter presenter;

    @Override
    public PaymentObservationResponse observeAnomalies() {
        // 시작·커밋 실패까지 번역하려고 Processor 프록시 호출 전체를 트랜잭션 밖에서 감싼다.
        try {
            var observation = processor.observeAnomalies();
            return presenter.toResponse(new PaymentObservationInfo(observation.observedAt(),
                toBucket(observation.unknown()),
                toBucket(observation.manualReviewUnknown()),
                toBucket(observation.inProgress()),
                toBucket(observation.returnedReady()),
                toBucket(observation.lateApproved()),
                toBucket(observation.doneOrderUnsynced())));
        } catch (DataAccessException | TransactionException exception) {
            var errorCode = PaymentObservationErrorCode.OBSERVATION_UNAVAILABLE;
            log.warn("결제 이상 후보 관측 실패. code={}, causeType={}", errorCode.getCode(), exception.getClass().getSimpleName());
            throw new PaymentObservationException(errorCode);
        }
    }

    private PaymentObservationInfo.Bucket toBucket(PaymentAnomalyObservation.Bucket bucket) {
        return new PaymentObservationInfo.Bucket(bucket.count(), bucket.oldestAt());
    }
}
