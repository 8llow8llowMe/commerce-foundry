package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAnomalyObservationRepository;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentAnomalyObservationAdapter implements PaymentAnomalyObservationPort {
    private final PaymentAnomalyObservationRepository repository;

    @Override
    public PaymentAnomalyObservation observeAnomalies(Instant observedAt) {
        var row = repository.observeAnomalies(observedAt);
        if (row.getInvalidTimestampCount() > 0) {
            throw new DataIntegrityViolationException("결제 관측 기준 시각이 유효하지 않습니다.");
        }
        return new PaymentAnomalyObservation(observedAt,
            new PaymentAnomalyObservation.Bucket(row.getUnknownCount(), row.getUnknownOldestAt()),
            new PaymentAnomalyObservation.Bucket(row.getManualReviewUnknownCount(), row.getManualReviewUnknownOldestAt()),
            new PaymentAnomalyObservation.Bucket(row.getInProgressCount(), row.getInProgressOldestAt()),
            new PaymentAnomalyObservation.Bucket(row.getReturnedReadyCount(), row.getReturnedReadyOldestAt()),
            new PaymentAnomalyObservation.Bucket(row.getLateApprovedCount(), row.getLateApprovedOldestAt()),
            new PaymentAnomalyObservation.Bucket(row.getDoneOrderUnsyncedCount(), row.getDoneOrderUnsyncedOldestAt()));
    }
}
