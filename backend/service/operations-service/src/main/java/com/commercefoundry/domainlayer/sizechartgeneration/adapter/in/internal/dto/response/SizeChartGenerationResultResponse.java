package com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record SizeChartGenerationResultResponse(
    @Schema(description = "초안 식별자")
    String draftId,

    @Schema(description = "작업 식별자")
    String jobId,

    @Schema(description = "영수증에 저장된 입력 SHA-256")
    String inputHash,

    @Schema(description = "작업 상태", allowableValues = {"PENDING", "SUCCEEDED", "FAILED", "CANCELLED"})
    String status,

    @Schema(description = "실패 시 허용된 코드, 그 외에는 없음")
    String failureCode,

    @Schema(description = "성공 확정된 JSON 객체 문자열, UTF-8 최대 32KiB, 그 외에는 없음")
    String resultJson
) {
    @Override
    public String toString() {
        return "SizeChartGenerationResultResponse[jobId=" + jobId + ", status=" + status + "]";
    }
}
