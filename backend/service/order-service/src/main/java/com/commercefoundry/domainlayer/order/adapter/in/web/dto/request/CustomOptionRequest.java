package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation.NormalizedMaxLength;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 맞춤 제작 입력 한 칸. 장바구니 요청과 같은 모양 · 같은 형식 규칙이지만 DTO 를 공유하지 않는다 (컨텍스트마다 자기 계약).
 * 값이 상품의 입력 항목 정의(필수 · 범위 · 단위)에 맞는지는 아직 보지 않는다 (#97).
 */
@Schema(description = "맞춤 제작 입력 한 칸")
public record CustomOptionRequest(

    @Schema(description = "입력 항목 키 — 상품 상세 customOrder.fields[].fieldKey. 영문 소문자로 시작하는 영문 · 숫자 · 밑줄 50자 이하", example = "chest")
    @NotBlank(message = OrderValidationMessage.FIELD_KEY_REQUIRED)
    @Pattern(regexp = "^[a-z][a-zA-Z0-9_]{0,49}$", message = OrderValidationMessage.FIELD_KEY_PATTERN)
    String fieldKey,

    @Schema(description = "입력값 (앞뒤 공백을 자르고 NFC 정규화한 뒤 500자 이하). 원문 그대로 저장 · 응답한다 — 화면 · 메일 · CSV 는 텍스트로 이스케이프한다", example = "96.5")
    @NotBlank(message = OrderValidationMessage.VALUE_REQUIRED)
    @NormalizedMaxLength(max = CustomOptionRequest.VALUE_MAX_LENGTH, message = OrderValidationMessage.VALUE_LENGTH_MAX)
    String value
) {

    /** {@code order_item_custom_option.field_value} 길이. */
    public static final int VALUE_MAX_LENGTH = 500;

    OrderCustomOption toCustomOption() {
        return new OrderCustomOption(fieldKey, value);
    }
}
