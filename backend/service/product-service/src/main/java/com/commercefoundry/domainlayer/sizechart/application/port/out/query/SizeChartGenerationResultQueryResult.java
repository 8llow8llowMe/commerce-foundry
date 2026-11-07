package com.commercefoundry.domainlayer.sizechart.application.port.out.query;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;

public record SizeChartGenerationResultQueryResult(
    long draftId, long jobId, String inputHash, SizeChartGenerationStatus status, String failureCode, String resultJson
) {
    @Override
    public String toString() {
        return "SizeChartGenerationResultQueryResult[jobId=" + jobId + ", status=" + status + "]";
    }
}
