package com.commercefoundry.domainlayer.sizechart.application.model;

public record SizeChartGenerationInputInfo(long draftId, String inputHash, String sourceJson, String templateJson) {
    @Override
    public String toString() {
        return "SizeChartGenerationInputInfo[redacted]";
    }
}
