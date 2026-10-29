package com.commercefoundry.domainlayer.skucost.application.info;

import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import java.time.Instant;
import java.util.List;

/**
 * 한 SKU 의 한 시각에 적용되는 유형별 원가.
 *
 * @param at    마이크로초로 자른 조회 시각
 * @param costs 유형 선언 순서(GOODS → PACKAGING). 그 시각에 적용할 행이 없는 유형은 빠진다 — 비어 있으면 "SKU 는 있으나 원가 미입력" 이다
 */
public record SkuCostsAtInfo(long skuId, Instant at, List<SkuCost> costs) {

    public SkuCostsAtInfo {
        costs = List.copyOf(costs);
    }
}
