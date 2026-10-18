package com.commercefoundry.domainlayer.product.domain.model;

/**
 * SKU 한 건의 재고 ({@code stock} 테이블 한 행). 행이 없는 SKU 는 품절이다 ({@link StockSnapshot#empty()}).
 *
 * <p>수량을 바꾸는 쓰기는 도메인 메서드가 아니라 조건부 UPDATE 한 문장으로 한다 — 읽고 고쳐 쓰면 동시 주문에서 초과 판매가 난다
 * ({@code coding-conventions.md} §9-5).
 *
 * @param stockManaged 재고를 관리하는 SKU 인가. false 면 무제한이다
 * @param availableQuantity 판매 가능 수량 (선점분 제외, 0 이상)
 */
public record Stock(long skuId, boolean stockManaged, long availableQuantity) {

    public Stock {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("판매 가능 수량은 음수일 수 없습니다: " + availableQuantity);
        }
    }

    public StockSnapshot toSnapshot() {
        return new StockSnapshot(stockManaged, availableQuantity);
    }
}
