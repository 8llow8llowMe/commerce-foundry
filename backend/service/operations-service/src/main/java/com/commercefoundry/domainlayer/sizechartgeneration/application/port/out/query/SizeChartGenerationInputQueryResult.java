package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query;

/** Immutable Product snapshot; both JSON strings must already be sanitized chart data. */
public record SizeChartGenerationInputQueryResult(long draftId, String inputHash, String sourceJson, String templateJson) {
    @Override
    public String toString() {
        return "SizeChartGenerationInputQueryResult[redacted]";
    }
}
