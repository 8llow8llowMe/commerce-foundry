package com.commercefoundry.domainlayer.sizechartgeneration.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Immutable registration identity; retained after the job finishes and the draft expires. */
public record SizeChartGenerationReceipt(long draftId, long jobId, String inputHash, long requestedByMemberId, Instant expiresAt) {
    public SizeChartGenerationReceipt {
        if (draftId <= 0 || jobId <= 0 || requestedByMemberId <= 0 || inputHash == null || !inputHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid size chart registration identity");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
