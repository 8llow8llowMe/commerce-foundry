package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 저장하는 판매 상태 (상품 · SKU). 품절은 저장하지 않는다 — 재고에서 유도한다 ({@link SaleAvailability}).
 */
public enum SaleStatus {

    ON_SALE,
    STOP_SELLING
}
