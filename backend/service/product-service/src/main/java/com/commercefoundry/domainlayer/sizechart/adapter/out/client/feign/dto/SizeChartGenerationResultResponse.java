package com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

public record SizeChartGenerationResultResponse(
    @JsonDeserialize(using = StrictString.class) String draftId,
    @JsonDeserialize(using = StrictString.class) String jobId,
    @JsonDeserialize(using = StrictString.class) String inputHash,
    @JsonDeserialize(using = StrictString.class) String status,
    @JsonDeserialize(using = StrictString.class) String failureCode,
    @JsonDeserialize(using = StrictString.class) String resultJson
) {
    @Override
    public String toString() {
        return "SizeChartGenerationResultResponse[redacted]";
    }
}
