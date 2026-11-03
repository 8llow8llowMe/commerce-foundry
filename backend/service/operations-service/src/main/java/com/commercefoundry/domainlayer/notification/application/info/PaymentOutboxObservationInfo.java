package com.commercefoundry.domainlayer.notification.application.info;

import java.time.Instant;

public record PaymentOutboxObservationInfo(Instant observedAt, long readyUnpublishedCount, Instant oldestReadyAvailableAt) {
}
