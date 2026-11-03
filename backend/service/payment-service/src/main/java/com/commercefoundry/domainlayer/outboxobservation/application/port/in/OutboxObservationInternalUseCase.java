package com.commercefoundry.domainlayer.outboxobservation.application.port.in;

import com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.dto.response.OutboxObservationResponse;

public interface OutboxObservationInternalUseCase {
    OutboxObservationResponse observeReady();
}
