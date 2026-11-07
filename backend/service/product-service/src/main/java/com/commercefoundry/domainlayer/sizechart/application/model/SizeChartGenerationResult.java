package com.commercefoundry.domainlayer.sizechart.application.model;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;

public record SizeChartGenerationResult(
    long draftId, long jobId, String inputHash, SizeChartGenerationStatus status, String failureCode, String resultJson
) {
    @Override
    public String toString() {
        return "SizeChartGenerationResult[jobId=" + jobId + ", status=" + status + "]";
    }
}
