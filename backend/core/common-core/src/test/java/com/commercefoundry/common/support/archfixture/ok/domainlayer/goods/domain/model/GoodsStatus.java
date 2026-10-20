package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;

/** 도메인 enum 은 common.dto.metadata 의 CodeNameDescribable 을 구현할 수 있다 — H12 예외. */
public enum GoodsStatus implements CodeNameDescribable {
    ON_SALE("판매 중");

    private final String displayName;

    GoodsStatus(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }
}
