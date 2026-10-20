package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 재고 복원 항목 한 건 — SKU 하나와 수량.
 *
 * @param id 항목 아이디 (Snowflake)
 * @param quantity 1 이상. 재고를 되돌리지 않은 항목(무제한 SKU)도 복원 상한 계산에 들어간다
 * @param restored 재고를 되돌릴 대상이었는가 — 선점 때 차감한({@link StockReservationItem#deducted()}) SKU 만 true 다. 재고 행이 없으면 warn 만 남기고
 *     수량은 늘지 않는다(true 그대로). 무제한 SKU 는 false 로 기록만 남긴다
 */
public record StockRestorationItem(long id, long skuId, int quantity, boolean restored) {

    public StockRestorationItem {
        if (quantity <= 0) {
            throw new IllegalArgumentException("복원 수량은 1 이상이어야 합니다: " + quantity);
        }
    }
}
