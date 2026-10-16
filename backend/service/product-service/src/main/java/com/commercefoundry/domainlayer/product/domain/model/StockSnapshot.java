package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 판매 가능 판정에 쓰는 재고 상태. 수량은 별도 {@code stock} 테이블이 가진다 (재고 이슈 #10).
 *
 * <p>재고 테이블이 생기기 전에는 모든 SKU 가 {@link #unlimited()} 다.
 *
 * @param stockManaged 재고를 관리하는 SKU 인가. false 면 수량과 무관하게 품절이 아니다
 * @param availableQuantity 판매 가능 수량 (선점분 제외)
 */
public record StockSnapshot(boolean stockManaged, long availableQuantity) {

    private static final StockSnapshot UNLIMITED = new StockSnapshot(false, 0L);

    public StockSnapshot {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("판매 가능 수량은 음수일 수 없습니다: " + availableQuantity);
        }
    }

    public static StockSnapshot unlimited() {
        return UNLIMITED;
    }

    public boolean isSoldOut() {
        return stockManaged && availableQuantity == 0L;
    }
}
