package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 배송비 견적 요청. 금액은 Wrapper({@code Long})로 받는다 — 필드가 빠진 요청이 0원으로 계산되지 않고 400 이 되게.
 */
@Schema(description = "서비스 간 배송비 견적 요청")
public record ShippingFeeQuoteInternalRequest(

    @Schema(description = "할인 후 상품 합계 (배송비 제외). 무료배송 기준과 비교한다")
    @NotNull(message = DeliveryValidationMessage.ORDER_AMOUNT_REQUIRED)
    @Valid
    OrderAmountRequest orderAmount,

    @Schema(description = "배송 국가 (ISO 3166-1 alpha-2 대문자). 지금은 KR 만 받는다", example = "KR")
    @NotBlank(message = DeliveryValidationMessage.COUNTRY_CODE_REQUIRED)
    @Pattern(regexp = "^[A-Z]{2}$", message = DeliveryValidationMessage.COUNTRY_CODE_FORMAT)
    String countryCode,

    @Schema(description = "배송지 우편번호 (숫자 5자리)", example = "63100")
    @NotBlank(message = DeliveryValidationMessage.POSTAL_CODE_REQUIRED)
    @Pattern(regexp = "^[0-9]{5}$", message = DeliveryValidationMessage.POSTAL_CODE_FORMAT)
    String postalCode
) {

    public ShippingFeeQuoteCommand toCommand() {
        return new ShippingFeeQuoteCommand(Money.of(orderAmount.amount(), orderAmount.currency()), countryCode, new PostalCode(postalCode));
    }

    @Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
    public record OrderAmountRequest(

        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원). 0 이상", example = "99000")
        @NotNull(message = DeliveryValidationMessage.AMOUNT_REQUIRED)
        @PositiveOrZero(message = DeliveryValidationMessage.AMOUNT_NON_NEGATIVE)
        Long amount,

        @Schema(description = "통화 코드 — 배송비 정책 통화와 같아야 한다", example = "KRW")
        @NotNull(message = DeliveryValidationMessage.CURRENCY_REQUIRED)
        CurrencyCode currency
    ) {
    }
}
