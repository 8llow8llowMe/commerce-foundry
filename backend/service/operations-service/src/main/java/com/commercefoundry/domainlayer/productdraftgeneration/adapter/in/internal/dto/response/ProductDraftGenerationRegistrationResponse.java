package com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProductDraftGenerationRegistrationResponse(
    @Schema(description = "초안 식별자")
    String draftId,

    @Schema(description = "생성 회차 (1~3)")
    int generationNo,

    @Schema(description = "작업 식별자")
    String jobId,

    @Schema(description = "영수증에 저장된 입력 SHA-256")
    String inputHash
) {
}
