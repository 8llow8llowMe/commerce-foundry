package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import java.time.Instant;

public interface PaymentAnomalyObservationPort {
    PaymentAnomalyObservation observeAnomalies(Instant observedAt);
}
