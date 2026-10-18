package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.domain.model.Stock;
import java.time.Instant;

/**
 * 재고 한 행과 마지막 수정 시각. 어드민 조정이 응답에 수정 시각을 싣는다 — 판매 판정에 쓰는 {@link Stock} 에는 시각을 두지 않는다.
 */
public record StockQueryResult(long skuId, boolean stockManaged, long availableQuantity, Instant updatedAt) {

    public Stock toStock() {
        return new Stock(skuId, stockManaged, availableQuantity);
    }
}
