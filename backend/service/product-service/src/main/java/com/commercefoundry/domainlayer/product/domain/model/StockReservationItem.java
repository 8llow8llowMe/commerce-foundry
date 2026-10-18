package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 재고 선점 항목 한 건 — SKU 하나와 수량.
 *
 * @param id 항목 아이디 (Snowflake)
 * @param quantity 1 이상
 * @param deducted 선점 때 재고를 실제로 차감했는가. 무제한(재고 관리 안 함) SKU 는 차감하지 않으므로 false 이고, 해제 · 만료 때 되돌리지 않는다
 */
public record StockReservationItem(long id, long skuId, int quantity, boolean deducted) {

    public StockReservationItem {
        if (quantity <= 0) {
            throw new IllegalArgumentException("선점 수량은 1 이상이어야 합니다: " + quantity);
        }
    }
}
