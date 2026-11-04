package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;

public interface PaymentAnomalyObservationPort {
    PaymentAnomalyObservation observe();
}
