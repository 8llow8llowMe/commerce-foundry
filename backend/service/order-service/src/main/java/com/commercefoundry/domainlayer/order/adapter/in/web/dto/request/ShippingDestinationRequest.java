package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation.ValidShippingDestination;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 미리보기의 배송지 — 배송비를 정하는 국가 · 우편번호만 받는다. 우편번호 규칙은 {@link ValidShippingDestination} 이 본다.
 */
@ValidShippingDestination
@Schema(description = "배송비를 정하는 배송지")
public record ShippingDestinationRequest(

    @Schema(description = "배송 국가 (ISO 3166-1 alpha-2 대문자). KR 외는 해외 배송이다", example = "KR")
    @NotBlank(message = OrderValidationMessage.COUNTRY_CODE_REQUIRED)
    @Pattern(regexp = "^[A-Z]{2}$", message = OrderValidationMessage.COUNTRY_CODE_FORMAT)
    String countryCode,

    @Schema(description = "우편번호 — 국내(KR)는 필수 · 숫자 5자리, 해외는 선택(영문 · 숫자 · 공백 · 하이픈 20자 이하)", example = "63100", nullable = true)
    String postalCode
) implements ValidShippingDestination.Fields {
}
