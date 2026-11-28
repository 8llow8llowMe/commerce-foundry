package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 재생성 요청 (#359 B3). 운영자가 보고 있던 회차를 받아 같은 요청을 다시 보내도 회차가 한 번만 오르게 한다 — 이미 다음 회차를 시작했으면 그 초안을 그대로 돌려준다.
 */
@Schema(description = "AI 상품 초안 재생성 요청")
public record ProductDraftRegenerateRequest(

    @Schema(description = "화면에 보이는 지금 회차(초안 응답의 generationNo, 1~3)", example = "1")
    @NotNull(message = ProductDraftValidationMessage.GENERATION_NO_REQUIRED)
    @Min(value = 1, message = ProductDraftValidationMessage.GENERATION_NO_RANGE)
    @Max(value = 3, message = ProductDraftValidationMessage.GENERATION_NO_RANGE)
    Integer generationNo
) {
}
