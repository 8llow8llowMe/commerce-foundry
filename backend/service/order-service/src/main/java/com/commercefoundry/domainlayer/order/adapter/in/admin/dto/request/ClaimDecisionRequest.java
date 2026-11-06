package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 어드민 클레임 수습 결정 — 거절 · 수동 환불 확정 · 복원 생략 종결 (#17 PR-A). */
@Schema(description = "클레임 수습 결정")
public record ClaimDecisionRequest(

    @Schema(description = "처리 사유 (1~200자) — 클레임의 결정 칸과 주문 이력(관리자)에 남는다. 고객 개인정보를 적지 않는다", example = "PG 거절 확인, 고객 안내 완료")
    @NotBlank(message = OrderValidationMessage.CLAIM_DECISION_REASON_REQUIRED)
    @Size(max = 200, message = OrderValidationMessage.REASON_LENGTH_MAX)
    @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]*", message = OrderValidationMessage.REASON_INVALID_CHARACTER)
    String reason
) {
}
