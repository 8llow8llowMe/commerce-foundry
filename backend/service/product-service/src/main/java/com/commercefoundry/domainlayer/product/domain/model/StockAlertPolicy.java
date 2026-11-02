package com.commercefoundry.domainlayer.product.domain.model;

import java.time.Instant;

/** SKU별 품절 임박 기준. 재고 수량과 독립이며, 행이 없으면 점검하지 않는다. */
public record StockAlertPolicy(long skuId, long thresholdQuantity, Instant updatedAt) {

    public StockAlertPolicy {
        if (skuId <= 0 || thresholdQuantity < 0) {
            throw new IllegalArgumentException("SKU 아이디는 양수이고 기준 수량은 0 이상이어야 합니다.");
        }
    }

    public static StockAlertPolicy configure(long skuId, long thresholdQuantity) {
        return new StockAlertPolicy(skuId, thresholdQuantity, null);
    }
}
