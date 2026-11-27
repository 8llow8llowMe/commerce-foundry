package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "엄격 검사된 본문 치수표 후보. 운영자가 검토한 텍스트와 출처를 함께 제출합니다")
public record SizeChartTextSourceResponse(
    @Schema(description = "본문 텍스트 후보 아이디", example = "TEXT-KO-1")
    String sourceId,

    @Schema(description = "검토한 본문 SHA-256 소문자 16진수 64자")
    String bodyHash,

    @Schema(description = "HTML과 자유 문장을 제외한 치수 텍스트 스냅숏", example = "가슴 총장 S 50 60")
    String sourceText
) {
    @Override
    public String toString() {
        return "SizeChartTextSourceResponse[redacted]";
    }
}
