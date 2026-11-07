package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.dto;

public record SizeChartGenerationInputInternalResponse(String draftId, String inputHash, String sourceJson, String templateJson) {
    @Override
    public String toString() {
        return "SizeChartGenerationInputInternalResponse[redacted]";
    }
}
