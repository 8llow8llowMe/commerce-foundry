package com.commercefoundry.domainlayer.sizechartgeneration.application.info;

public record SizeChartGenerationResultInfo(long draftId, long jobId, String inputHash, String status, String failureCode, String resultJson) {
    @Override
    public String toString() {
        return "SizeChartGenerationResultInfo[jobId=" + jobId + ", status=" + status + "]";
    }
}
