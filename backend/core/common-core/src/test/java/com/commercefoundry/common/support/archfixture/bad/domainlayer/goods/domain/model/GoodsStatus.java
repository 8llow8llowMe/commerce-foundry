package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;

/** 위반 예시 안의 허용 사례 — 도메인 enum 의 CodeNameDescribable(common.dto.metadata)은 H12 에 걸리지 않는다. */
public enum GoodsStatus implements CodeNameDescribable {
    ON_SALE;

    @Override
    public String getDisplayName() {
        return name();
    }
}
