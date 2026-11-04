package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentAnomalyObservationProcessor {
    private final PaymentAnomalyObservationPort repository;
    private final Clock clock;

    @Transactional(readOnly = true, timeout = 2)
    public PaymentAnomalyObservation observeAnomalies() {
        // PostgreSQL 정밀도로 한 번 고정한 시각을 쿼리와 응답에 함께 쓴다.
        return repository.observeAnomalies(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
