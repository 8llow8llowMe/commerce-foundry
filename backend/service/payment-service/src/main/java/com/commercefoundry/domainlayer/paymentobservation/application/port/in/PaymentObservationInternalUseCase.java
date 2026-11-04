package com.commercefoundry.domainlayer.paymentobservation.application.port.in;

import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.dto.response.PaymentObservationResponse;

public interface PaymentObservationInternalUseCase {
    PaymentObservationResponse observeAnomalies();
}
