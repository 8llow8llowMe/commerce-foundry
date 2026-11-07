package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "사이즈표 적용 결과")
public record SizeChartDraftApplyResponse(
    @Schema(description = "초안 아이디")
    String draftId,

    @Schema(description = "상품 아이디")
    String productId,

    @Schema(description = "상품 사이즈표 아이디")
    String chartId,

    @Schema(description = "적용한 사이즈표 버전")
    long chartRevision,

    @Schema(description = "적용 시각")
    Instant appliedAt
) {
}
