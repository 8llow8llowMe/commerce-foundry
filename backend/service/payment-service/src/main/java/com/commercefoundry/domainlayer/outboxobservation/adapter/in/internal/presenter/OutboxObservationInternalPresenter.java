package com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.dto.response.OutboxObservationResponse;
import com.commercefoundry.domainlayer.outboxobservation.application.info.OutboxObservationInfo;
import org.springframework.stereotype.Component;

@Component
public class OutboxObservationInternalPresenter {
    public OutboxObservationResponse toResponse(OutboxObservationInfo info) {
        return new OutboxObservationResponse(info.observedAt(), info.readyUnpublishedCount(), info.oldestReadyAvailableAt());
    }
}
