package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "사이즈표 초안 목록 항목")
public record SizeChartDraftSummaryResponse(
    @Schema(description = "초안 아이디")
    String draftId,

    @Schema(description = "상품 아이디")
    String productId,

    @Schema(description = "템플릿 아이디")
    String templateId,

    @Schema(description = "생성 작업 아이디")
    String jobId,

    @Schema(description = "초안 상태")
    SizeChartDraftResponse.Metadata status,

    @Schema(description = "검토 버전")
    long version,

    @Schema(description = "초안 만료 시각")
    Instant expiresAt
) {
}
