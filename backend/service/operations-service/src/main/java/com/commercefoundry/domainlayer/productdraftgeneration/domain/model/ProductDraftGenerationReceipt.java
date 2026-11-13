package com.commercefoundry.domainlayer.productdraftgeneration.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable registration identity keyed by (draftId, generationNo); retained after the job finishes and the draft expires.
 * The generation number is assigned by product-service and capped at {@link #MAX_GENERATION_NO} (also enforced by a DB CHECK).
 */
public record ProductDraftGenerationReceipt(long draftId, int generationNo, long jobId, String inputHash, long requestedByMemberId, Instant expiresAt) {
    public static final int MIN_GENERATION_NO = 1;
    public static final int MAX_GENERATION_NO = 3;

    public ProductDraftGenerationReceipt {
        if (draftId <= 0 || generationNo < MIN_GENERATION_NO || generationNo > MAX_GENERATION_NO || jobId <= 0 || requestedByMemberId <= 0
            || inputHash == null || !inputHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid product draft generation registration identity");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
