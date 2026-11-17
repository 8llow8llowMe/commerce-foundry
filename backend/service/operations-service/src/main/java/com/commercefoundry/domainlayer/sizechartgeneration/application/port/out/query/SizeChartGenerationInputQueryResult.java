package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query;

import java.util.List;

/**
 * Immutable Product snapshot; both JSON strings must already be sanitized chart data.
 *
 * @param mode   {@code TEXT} or {@code IMAGE}. Validation compares it with the mode inside {@code sourceJson}
 * @param images IMAGE metadata in read order (index · SHA-256 · MIME). Empty for TEXT
 */
public record SizeChartGenerationInputQueryResult(long draftId, String inputHash, String sourceJson, String templateJson, String mode,
                                                  List<Image> images) {

    public SizeChartGenerationInputQueryResult {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** TEXT snapshot (no images). */
    public SizeChartGenerationInputQueryResult(long draftId, String inputHash, String sourceJson, String templateJson) {
        this(draftId, inputHash, sourceJson, templateJson, "TEXT", List.of());
    }

    /** One approved image as Product pinned it. Never carries a storage key. */
    public record Image(int index, String sha256, String contentType) {
    }

    @Override
    public String toString() {
        return "SizeChartGenerationInputQueryResult[redacted]";
    }
}
