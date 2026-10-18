package com.commercefoundry.domainlayer.cart.application.model;

import java.util.Objects;

/**
 * 병합에서 건너뛴 항목 하나. 같은 SKU 라도 항목마다 따로 판정한다 — 입력이 다른 항목 하나는 담기고 하나는 건너뛸 수 있다.
 *
 * @param index 병합 요청 items 의 0부터 위치
 */
public record CartMergeSkip(int index, long skuId, CartMergeSkipReason reason) {

    public CartMergeSkip {
        Objects.requireNonNull(reason, "reason");
    }
}
