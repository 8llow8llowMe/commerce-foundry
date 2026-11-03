package com.commercefoundry.domainlayer.outboxobservation.application.info;

import java.time.Instant;

public record OutboxObservationInfo(Instant observedAt, long readyUnpublishedCount, Instant oldestReadyAvailableAt) {
}
