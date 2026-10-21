package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 배송비 견적 요청. 금액은 Wrapper({@code Long})로 받는다 — 필드가 빠진 요청이 0원으로 계산되지 않고 400 이 되게.
 *
 * <p>우편번호 · 무게는 국가에 따라 필수 여부와 형식이 달라 필드 제약이 아니라 {@link ValidQuoteDestination}(클래스 수준)이 본다.
 * 오류는 필드 제약과 같은 모양({@code fieldErrors[].field} = {@code postalCode} · {@code weightGrams})으로 나간다.
 * 하위 호환이다 — 국내(KR) 요청은 #11 과 같은 본문 · 같은 오류 코드다.
 */
@ValidQuoteDestination
@Schema(description = "서비스 간 배송비 견적 요청")
public record ShippingFeeQuoteInternalRequest(

    @Schema(description = "할인 후 상품 합계 (배송비 제외). 무료배송 기준과 비교한다")
    @NotNull(message = DeliveryValidationMessage.ORDER_AMOUNT_REQUIRED)
    @Valid
    OrderAmountRequest orderAmount,

    @Schema(description = "배송 국가 (ISO 3166-1 alpha-2 대문자). KR 은 국내 정책, 그 밖은 해외 정책으로 계산한다", example = "KR")
    @NotBlank(message = DeliveryValidationMessage.COUNTRY_CODE_REQUIRED)
    @Pattern(regexp = "^[A-Z]{2}$", message = DeliveryValidationMessage.COUNTRY_CODE_FORMAT)
    String countryCode,

    @Schema(
        description = "배송지 우편번호. KR 이면 필수(숫자 5자리), 그 밖의 국가는 선택(영문 · 숫자 · 공백 · 하이픈 20자 이하) — 해외 배송비 계산에는 쓰지 않는다",
        example = "63100", nullable = true
    )
    String postalCode,

    @Schema(description = "배송할 상품 총 무게 (그램, 1 이상) — SKU 무게 × 수량 합. KR 외 국가면 필수, KR 이면 무시한다", example = "1200", nullable = true)
    Integer weightGrams
) {

    /** 국내면 무게를, 해외면 우편번호를 버린다 — 각 범위의 계산에 쓰지 않는 값이다. */
    public ShippingFeeQuoteCommand toCommand() {
        Money amount = Money.of(orderAmount.amount(), orderAmount.currency());
        if (ShippingScope.ofCountry(countryCode).orElse(null) == ShippingScope.DOMESTIC) {
            return ShippingFeeQuoteCommand.domestic(amount, new PostalCode(postalCode));
        }
        return ShippingFeeQuoteCommand.international(amount, countryCode, weightGrams);
    }

    @Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
    public record OrderAmountRequest(

        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원, USD 는 센트). 0 이상", example = "99000")
        @NotNull(message = DeliveryValidationMessage.AMOUNT_REQUIRED)
        @PositiveOrZero(message = DeliveryValidationMessage.AMOUNT_NON_NEGATIVE)
        Long amount,

        @Schema(description = "통화 코드 — 배송비 정책 통화와 같아야 한다. 다르면 환산하지 않고 400(DELIVERY_003)", example = "KRW")
        @NotNull(message = DeliveryValidationMessage.CURRENCY_REQUIRED)
        CurrencyCode currency
    ) {
    }
}
