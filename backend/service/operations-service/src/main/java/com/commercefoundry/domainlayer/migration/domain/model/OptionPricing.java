package com.commercefoundry.domainlayer.migration.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 원본 SKU 판매가를 기본가 + 0 이상 추가금으로 바꾼다 ({@code migration-guide.md} §5). 이 엔진은 SKU 추가금이 0 이상이고
 * "기본 판매가 = 판매중 SKU 최저가" 를 전제한다(#8) — 원본에 음수 옵션가가 있어도 같은 규칙으로 기본가를 최저가로 옮긴다.
 *
 * @param basePrice 기본 판매가 — SKU 판매가 중 최저
 * @param additionalPrices SKU 순서대로의 추가금(0 이상)
 * @param listPrice 정가. 기본가보다 낮으면 null — 정가가 판매가보다 낮게 보이지 않게
 */
public record OptionPricing(long basePrice, List<Long> additionalPrices, Long listPrice) {

    public OptionPricing {
        additionalPrices = List.copyOf(additionalPrices);
    }

    /**
     * @param skuSalePrices SKU 마다의 최종 판매가(원본의 기본가 + 옵션가). 비어 있으면 안 된다
     * @param listPrice 원본 정가. 없으면 null
     */
    public static OptionPricing of(List<Long> skuSalePrices, Long listPrice) {
        Objects.requireNonNull(skuSalePrices, "skuSalePrices");
        if (skuSalePrices.isEmpty()) {
            throw new IllegalArgumentException("SKU 판매가가 하나 이상 있어야 합니다.");
        }
        long base = Long.MAX_VALUE;
        for (Long price : skuSalePrices) {
            if (price == null || price < 0) {
                throw new IllegalArgumentException("SKU 판매가는 0 이상이어야 합니다.");
            }
            base = Math.min(base, price);
        }
        long basePrice = base;
        List<Long> additional = skuSalePrices.stream().map(price -> price - basePrice).toList();
        return new OptionPricing(basePrice, additional, listPrice != null && listPrice >= basePrice ? listPrice : null);
    }
}
