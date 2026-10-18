package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 판매 가능 판정에 쓰는 재고 상태. 수량은 {@code stock} 테이블이 가진다 ({@link Stock}).
 *
 * <p><b>재고 행이 없는 SKU 는 품절이다</b> ({@link #empty()}) — 닫힌 기본값이다. 재고를 등록하지 않은 SKU 가 무제한으로 팔리지 않게 한다.
 * SKU 아이디로 찾을 때는 {@link SkuStocks#snapshotOf} 를 거쳐 이 규칙을 한 곳에서 지킨다.
 *
 * @param stockManaged 재고를 관리하는 SKU 인가. false 면 수량과 무관하게 품절이 아니다 (무제한)
 * @param availableQuantity 판매 가능 수량 (선점분 제외)
 */
public record StockSnapshot(boolean stockManaged, long availableQuantity) {

    private static final StockSnapshot UNLIMITED = new StockSnapshot(false, 0L);
    private static final StockSnapshot EMPTY = new StockSnapshot(true, 0L);

    public StockSnapshot {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("판매 가능 수량은 음수일 수 없습니다: " + availableQuantity);
        }
    }

    /** 재고를 관리하지 않는 SKU — 수량과 무관하게 품절이 아니다. */
    public static StockSnapshot unlimited() {
        return UNLIMITED;
    }

    /** 재고 행이 없는 SKU — 재고 관리 · 수량 0 으로 보아 품절이다. */
    public static StockSnapshot empty() {
        return EMPTY;
    }

    public boolean isSoldOut() {
        return stockManaged && availableQuantity == 0L;
    }
}
