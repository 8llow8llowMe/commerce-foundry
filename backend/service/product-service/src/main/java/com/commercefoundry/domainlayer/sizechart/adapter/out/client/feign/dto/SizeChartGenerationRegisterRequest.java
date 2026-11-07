package com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto;

import java.time.Instant;

public record SizeChartGenerationRegisterRequest(long draftId, String inputHash, long requestedByMemberId, Instant expiresAt) {
}
