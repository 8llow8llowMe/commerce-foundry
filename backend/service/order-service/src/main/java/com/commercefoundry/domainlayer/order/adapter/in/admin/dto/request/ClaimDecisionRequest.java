package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 어드민 클레임 수동 환불 확정 · 복원 생략 종결 (#17 A3) — 운영자가 본 상세 화면의 버전과 판단 근거.
 *
 * <p>사유의 "비었음"은 도메인 {@link com.commercefoundry.domainlayer.order.domain.model.Claim.Decision}({@code String#isBlank}, 즉
 * {@code Character#isWhitespace})과 같은 기준이다 — {@code @NotBlank} 는 U+0020 이하만 공백으로 보므로 전각 공백(U+3000) · 줄 구분자(U+2028)만 있는 사유를
 * 따로 {@code ORDER_170} 으로 막는다. 금지 문자는 다른 사유 DTO 보다 넓게 서식 문자(Cf) · 줄 · 문단 구분자(Zl · Zp)까지 막되, 공백만인 값은 {@code ORDER_170}
 * 하나로 답하도록 금지 문자 검사에서 뺀다.
 */
@Schema(description = "클레임 운영자 결정")
public record ClaimDecisionRequest(
    @Schema(description = "상세 조회 응답의 recovery.version", example = "3")
    @NotNull(message = OrderValidationMessage.CLAIM_EXPECTED_VERSION)
    @PositiveOrZero(message = OrderValidationMessage.CLAIM_EXPECTED_VERSION)
    Long expectedVersion,

    @Schema(description = "판단 근거 (1~200자) — 예: '결제 관리 화면에서 취소 완료 확인'. 고객 개인정보 · 계좌번호를 적지 않는다", example = "결제 관리 화면에서 취소 완료 확인")
    @NotBlank(message = OrderValidationMessage.CLAIM_DECISION_REASON_REQUIRED)
    @Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = OrderValidationMessage.CLAIM_DECISION_REASON_REQUIRED)
    @Size(max = 200, message = OrderValidationMessage.REASON_LENGTH_MAX)
    @Pattern(regexp = "[^\\p{Cc}\\p{Cs}\\p{Cf}\\p{Zl}\\p{Zp}]*|\\p{javaWhitespace}*", message = OrderValidationMessage.REASON_INVALID_CHARACTER)
    String reason
) {
}
