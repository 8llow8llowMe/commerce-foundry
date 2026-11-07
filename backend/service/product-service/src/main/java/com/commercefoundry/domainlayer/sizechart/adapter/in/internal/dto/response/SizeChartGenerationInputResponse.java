package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record SizeChartGenerationInputResponse(
    @Schema(description = "초안 식별자")
    String draftId,

    @Schema(description = "저장된 입력 SHA-256")
    String inputHash,

    @Schema(description = "개인정보를 제거한 불변 입력 JSON")
    String sourceJson,

    @Schema(description = "불변 템플릿 JSON")
    String templateJson
) {
    @Override
    public String toString() {
        return "SizeChartGenerationInputResponse[redacted]";
    }
}
