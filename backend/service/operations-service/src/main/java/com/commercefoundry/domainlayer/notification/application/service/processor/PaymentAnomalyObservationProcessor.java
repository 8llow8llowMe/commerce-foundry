package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentAnomalyObservationProcessor {
    private final PaymentAnomalyObservationPort observations;

    public PaymentAnomalyObservation observe() {
        return observations.observe();
    }
}
