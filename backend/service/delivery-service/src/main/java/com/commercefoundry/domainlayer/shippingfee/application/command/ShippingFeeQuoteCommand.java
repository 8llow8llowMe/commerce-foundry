package com.commercefoundry.domainlayer.shippingfee.application.command;

import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.Objects;

/**
 * 배송비 견적 요청. 국가에 따라 쓰는 값이 다르다 — 형식 검증은 요청 DTO 가 끝냈고, 여기서는 범위별 필수 값만 다시 확인한다.
 *
 * @param orderAmount 할인 후 상품 합계 (배송비 제외). 무료배송 기준과 비교만 한다 — 계산 주체는 order-service 다
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 * @param postalCode 국내 우편번호. KR 이면 필수, 그 밖의 국가는 null — 해외 배송비 계산에 쓰지 않는다
 * @param weightGrams 배송할 상품 총 무게 (그램). KR 외 국가면 필수(1 이상), KR 이면 null — 국내 배송비 계산에 쓰지 않는다
 */
public record ShippingFeeQuoteCommand(Money orderAmount, String countryCode, PostalCode postalCode, Integer weightGrams) {

    public ShippingFeeQuoteCommand {
        Objects.requireNonNull(orderAmount, "orderAmount");
        Objects.requireNonNull(countryCode, "countryCode");
        // 범위 판정은 ShippingScope.ofCountry 한 곳에 둔다 — 형식이 틀린 코드는 국내로 보지 않는다(요청 DTO 가 이미 거절했다)
        if (ShippingScope.ofCountry(countryCode).orElse(null) == ShippingScope.DOMESTIC) {
            Objects.requireNonNull(postalCode, "postalCode");
        } else {
            Objects.requireNonNull(weightGrams, "weightGrams");
            if (weightGrams <= 0) {
                throw new IllegalArgumentException("무게는 1g 이상이어야 합니다: " + weightGrams);
            }
        }
    }

    /** 국내 견적 — 무게를 쓰지 않는다. */
    public static ShippingFeeQuoteCommand domestic(Money orderAmount, PostalCode postalCode) {
        return new ShippingFeeQuoteCommand(orderAmount, ShippingScope.DOMESTIC_COUNTRY_CODE, postalCode, null);
    }

    /** 해외 견적 — 우편번호를 쓰지 않는다. */
    public static ShippingFeeQuoteCommand international(Money orderAmount, String countryCode, int weightGrams) {
        return new ShippingFeeQuoteCommand(orderAmount, countryCode, null, weightGrams);
    }
}
