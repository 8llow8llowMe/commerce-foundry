package com.commercefoundry.domainlayer.outboxobservation.application.port.out.query;

import java.time.Instant;

public record OutboxObservationQueryResult(long readyUnpublishedCount, Instant oldestReadyAvailableAt) {
}
