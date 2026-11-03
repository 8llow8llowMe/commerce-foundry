package com.commercefoundry.domainlayer.outboxobservation.application.port.out;

import com.commercefoundry.domainlayer.outboxobservation.application.port.out.query.OutboxObservationQueryResult;
import java.time.Instant;

public interface OutboxObservationRepositoryPort {
    OutboxObservationQueryResult observeReady(Instant observedAt);
}
