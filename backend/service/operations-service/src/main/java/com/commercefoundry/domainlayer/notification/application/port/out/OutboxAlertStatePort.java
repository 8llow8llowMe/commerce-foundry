package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.OutboxAlertState;
import java.util.Optional;

public interface OutboxAlertStatePort {
    Optional<OutboxAlertState> findLocked();
    void save(OutboxAlertState state, boolean firstObservation);
}
