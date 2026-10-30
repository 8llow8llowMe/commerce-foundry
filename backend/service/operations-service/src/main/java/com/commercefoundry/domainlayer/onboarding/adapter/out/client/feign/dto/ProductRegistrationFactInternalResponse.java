package com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.dto;

import java.time.Instant;

public record ProductRegistrationFactInternalResponse(Boolean storeReady, Instant memberFirstRegisteredAt) {
}
