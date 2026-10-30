package com.commercefoundry.domainlayer.product.application.info;

import java.time.Instant;

public record ProductRegistrationFactInfo(boolean storeReady, Instant memberFirstRegisteredAt) {
}
