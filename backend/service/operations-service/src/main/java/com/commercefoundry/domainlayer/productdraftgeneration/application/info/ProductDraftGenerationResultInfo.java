package com.commercefoundry.domainlayer.productdraftgeneration.application.info;

public record ProductDraftGenerationResultInfo(
    long draftId, int generationNo, long jobId, String inputHash, String status, String failureCode, String resultJson
) {
    @Override
    public String toString() {
        return "ProductDraftGenerationResultInfo[jobId=" + jobId + ", status=" + status + "]";
    }
}
