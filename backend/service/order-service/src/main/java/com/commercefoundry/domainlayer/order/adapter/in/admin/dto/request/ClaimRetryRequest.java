package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 어드민 클레임 다시 시도 (#17 A2b) — 운영자가 본 상세 화면의 버전이 지금 저장된 버전과 같을 때만 다시 보낸다. */
@Schema(description = "클레임 다시 시도")
public record ClaimRetryRequest(
    @Schema(description = "상세 조회 응답의 recovery.version", example = "3")
    @NotNull(message = OrderValidationMessage.CLAIM_EXPECTED_VERSION)
    @PositiveOrZero(message = OrderValidationMessage.CLAIM_EXPECTED_VERSION)
    Long expectedVersion
) {
}
