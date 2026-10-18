package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.cart.application.exception.CartValidationMessage;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 맞춤 제작 입력 한 칸. 형식만 본다 — 값이 상품의 입력 항목 정의(필수 · 범위 · 단위)에 맞는지는 주문 생성(#13)이 본다.
 */
@Schema(description = "맞춤 제작 입력 한 칸")
public record CustomOptionRequest(

    @Schema(description = "입력 항목 키 — 상품 상세 customOrder.fields[].fieldKey. 영문 소문자로 시작하는 영문 · 숫자 · 밑줄 50자 이하", example = "chest")
    @NotBlank(message = CartValidationMessage.FIELD_KEY_REQUIRED)
    @Pattern(regexp = "^[a-z][a-zA-Z0-9_]{0,49}$", message = CartValidationMessage.FIELD_KEY_PATTERN)
    String fieldKey,

    @Schema(description = "입력값 (500자 이하). 앞뒤 공백은 잘라 저장한다", example = "96.5")
    @NotBlank(message = CartValidationMessage.VALUE_REQUIRED)
    @Size(max = 500, message = CartValidationMessage.VALUE_LENGTH_MAX)
    String value
) {

    CustomOption toCustomOption() {
        return CustomOption.of(fieldKey, value);
    }
}
