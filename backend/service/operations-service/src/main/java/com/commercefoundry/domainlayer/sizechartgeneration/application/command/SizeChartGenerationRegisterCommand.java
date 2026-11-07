package com.commercefoundry.domainlayer.sizechartgeneration.application.command;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record SizeChartGenerationRegisterCommand(long draftId, String inputHash, long requestedByMemberId, Instant expiresAt) {
    public SizeChartGenerationRegisterCommand {
        // Match product persistence and timestamp(6) before comparing an original request with a reloaded one.
        expiresAt = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
    }
}
