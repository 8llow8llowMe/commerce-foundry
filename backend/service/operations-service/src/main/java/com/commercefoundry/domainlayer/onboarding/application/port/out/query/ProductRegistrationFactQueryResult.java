package com.commercefoundry.domainlayer.onboarding.application.port.out.query;

import java.time.Instant;

public record ProductRegistrationFactQueryResult(boolean storeReady, Instant memberFirstRegisteredAt) {
}
