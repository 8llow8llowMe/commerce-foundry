package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 배송비 견적 조건 ({@code ShippingFeeQueryPort#quote}). 어느 칸을 실을지는 부른 Processor 가 정하고 adapter 는 그대로 옮긴다 —
 * 국내(KR)면 우편번호(숫자 5자리)를 싣고 무게는 null, 해외면 총 무게(Σ SKU 무게 × 수량)를 싣고 우편번호는 있을 때만 싣는다 ({@code order-flow-guide.md} §2).
 *
 * <p>{@link #toString()} 은 우편번호를 가린다 — 배송지의 일부라 로그에 남기지 않는다 ({@code coding-conventions.md} §8).
 *
 * @param orderAmount 할인 후 상품 합계(배송비 제외). delivery 는 무료배송 기준과 비교만 한다. 통화가 그 범위 정책의 통화와 다르면 거절된다(환산하지 않는다, #70)
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 * @param postalCode 배송지 우편번호. KR 이면 필수, 그 밖의 국가는 선택(null)
 * @param weightGrams 배송할 상품 총 무게(그램, 1 이상). KR 외 국가면 필수, KR 이면 null
 */
public record ShippingFeeQuoteQuery(Money orderAmount, String countryCode, String postalCode, Integer weightGrams) {

    public ShippingFeeQuoteQuery {
        Objects.requireNonNull(orderAmount, "orderAmount");
        Objects.requireNonNull(countryCode, "countryCode");
    }

    @Override
    public String toString() {
        return "ShippingFeeQuoteQuery[orderAmount=%s, countryCode=%s, postalCode=%s, weightGrams=%s]"
            .formatted(orderAmount, countryCode, postalCode == null ? null : "***", weightGrams);
    }
}
