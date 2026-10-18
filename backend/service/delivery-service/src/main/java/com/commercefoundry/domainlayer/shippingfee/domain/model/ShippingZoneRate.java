package com.commercefoundry.domainlayer.shippingfee.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 국가 그룹의 무게 구간 하나 — 상한 무게까지의 배송 요금. 상한을 <b>포함</b>한다 ({@code 무게 <= maxWeightGrams}).
 *
 * <p>구간의 하한은 두지 않는다. 같은 그룹에서 바로 아래 구간의 상한 + 1g 이 이 구간의 시작이다 ({@link ShippingZone#findRate}).
 *
 * @param maxWeightGrams 상한 무게 (그램, 1 이상)
 * @param fee 무게 요금 (0 이상). 통화는 정책 통화와 같다 — {@link InternationalShippingPolicy} 가 확인한다
 */
public record ShippingZoneRate(long id, int maxWeightGrams, Money fee) {

    public ShippingZoneRate {
        Objects.requireNonNull(fee, "fee");
        if (maxWeightGrams <= 0) {
            throw new IllegalArgumentException("무게 구간 상한은 1g 이상이어야 합니다: " + maxWeightGrams);
        }
    }

    public boolean covers(int weightGrams) {
        return weightGrams <= maxWeightGrams;
    }
}
