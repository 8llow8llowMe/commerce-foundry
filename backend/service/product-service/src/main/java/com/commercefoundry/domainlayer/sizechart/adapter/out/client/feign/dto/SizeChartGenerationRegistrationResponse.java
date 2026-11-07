package com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

public record SizeChartGenerationRegistrationResponse(@JsonDeserialize(using = StrictString.class) String draftId,
    @JsonDeserialize(using = StrictString.class) String jobId,
    @JsonDeserialize(using = StrictString.class) String inputHash) {
}
