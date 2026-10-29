package com.commercefoundry.domainlayer.skucost.application.model;

import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;

/**
 * 원가 유형 입력 ({@code @RequestParam} · 요청 본문). 값은 {@link SkuCostType} 과 같다 — 컨트롤러가 domain 타입을 쓰지 않으므로
 * (H1) 입력 타입을 application/model 에 두고 command 로 옮길 때 domain 으로 바꾼다 (선례: {@code SizeChartUnitCode}).
 */
public enum SkuCostTypeCode {

    GOODS,
    PACKAGING;

    public SkuCostType toDomain() {
        return SkuCostType.valueOf(name());
    }
}
