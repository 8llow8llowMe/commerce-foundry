package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "작업과 연결된 초안 접수 결과")
public record SizeChartDraftAcceptedResponse(
    @Schema(description = "초안 아이디")
    String draftId,

    @Schema(description = "생성 작업 아이디")
    String jobId
) {
}
