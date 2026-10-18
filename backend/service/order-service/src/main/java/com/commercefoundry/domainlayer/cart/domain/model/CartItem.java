package com.commercefoundry.domainlayer.cart.domain.model;

import java.util.Objects;

/**
 * 장바구니 한 줄 — 회원 × SKU × 맞춤 제작 입력({@link CustomOptions#hash()}). 같은 줄을 다시 담으면 새 줄을 만들지 않고 수량을 더한다.
 *
 * <p>수량 상한은 {@value #MAX_QUANTITY}개와 상품의 1회 주문 최대 수량 중 작은 값이다({@link #maxQuantity}). 수량은 아래 메서드로만 바꾼다 —
 * 상한을 넘기는 변경은 {@link IllegalStateException} 이다. 호출하는 쪽(Processor)이 {@link #canAdd} · {@link #isQuantityAllowed} 로 먼저 보고
 * 사용자 오류로 바꾼다.
 *
 * @param version 낙관적 락 버전. 아직 저장하지 않은 줄이면 null
 */
public record CartItem(long id, long memberId, long skuId, int quantity, CustomOptions customOptions, Long version) {

    /** 줄당 수량 상한. 상품의 1회 주문 최대 수량이 더 작으면 그것이 상한이다. */
    public static final int MAX_QUANTITY = 99;
    /** 장바구니 줄 수 상한. */
    public static final int MAX_LINES_PER_CART = 50;

    public CartItem {
        Objects.requireNonNull(customOptions, "customOptions");
        if (quantity < 1) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + quantity);
        }
    }

    /**
     * 새 줄. 저장 전이라 버전이 없다.
     *
     * @throws IllegalStateException 수량이 상한을 넘을 때
     */
    public static CartItem create(long id, long memberId, long skuId, int quantity, CustomOptions customOptions, int maxQuantity) {
        if (!isQuantityAllowed(quantity, maxQuantity)) {
            throw new IllegalStateException("수량이 상한을 넘습니다: quantity=%d, max=%d".formatted(quantity, maxQuantity));
        }
        return new CartItem(id, memberId, skuId, quantity, customOptions, null);
    }

    /**
     * 줄 수량 상한 = min({@value #MAX_QUANTITY}, 상품의 1회 주문 최대 수량).
     *
     * @param maxQuantityPerOrder 상품의 1회 주문 최대 수량. null 이면 제한 없음
     */
    public static int maxQuantity(Integer maxQuantityPerOrder) {
        return maxQuantityPerOrder == null ? MAX_QUANTITY : Math.min(MAX_QUANTITY, maxQuantityPerOrder);
    }

    /** 1 이상 상한 이하인가. */
    public static boolean isQuantityAllowed(int quantity, int maxQuantity) {
        return quantity >= 1 && quantity <= maxQuantity;
    }

    public boolean isNew() {
        return version == null;
    }

    /** 같은 줄인가 — SKU 와 맞춤 제작 입력이 모두 같다. */
    public boolean isSameLine(long otherSkuId, CustomOptions otherCustomOptions) {
        return skuId == otherSkuId && customOptions.sameAs(otherCustomOptions);
    }

    /** 지금 수량에 더해도 상한 이하인가. */
    public boolean canAdd(int addition, int maxQuantity) {
        return addition >= 1 && (long) quantity + addition <= maxQuantity;
    }

    /**
     * 수량을 더한다 (같은 줄을 다시 담기).
     *
     * @throws IllegalStateException 합계가 상한을 넘을 때 — {@link #canAdd} 로 먼저 본다
     */
    public CartItem addQuantity(int addition, int maxQuantity) {
        if (!canAdd(addition, maxQuantity)) {
            throw new IllegalStateException("수량이 상한을 넘습니다: quantity=%d, addition=%d, max=%d".formatted(quantity, addition, maxQuantity));
        }
        return withQuantity(quantity + addition);
    }

    /**
     * 수량을 더하되 상한에서 자른다 (로그인 직후 병합). 이미 상한 이상이면 그대로 둔다 — 병합이 수량을 줄이지 않는다.
     */
    public CartItem addQuantityUpTo(int addition, int maxQuantity) {
        if (addition < 1) {
            throw new IllegalArgumentException("더할 수량은 1 이상이어야 합니다: " + addition);
        }
        long added = (long) quantity + addition;
        int capped = (int) Math.max(quantity, Math.min(added, maxQuantity));
        return capped == quantity ? this : withQuantity(capped);
    }

    /**
     * 수량을 바꾼다.
     *
     * @throws IllegalStateException 1 미만이거나 상한을 넘을 때 — {@link #isQuantityAllowed} 로 먼저 본다
     */
    public CartItem changeQuantity(int newQuantity, int maxQuantity) {
        if (!isQuantityAllowed(newQuantity, maxQuantity)) {
            throw new IllegalStateException("수량이 허용 범위 밖입니다: quantity=%d, max=%d".formatted(newQuantity, maxQuantity));
        }
        return withQuantity(newQuantity);
    }

    /** 지금 수량이 상한을 넘는가 — 담은 뒤 상품의 1회 주문 최대 수량이 줄어든 경우. 조회 화면에 표시한다. */
    public boolean exceeds(int maxQuantity) {
        return quantity > maxQuantity;
    }

    private CartItem withQuantity(int newQuantity) {
        return new CartItem(id, memberId, skuId, newQuantity, customOptions, version);
    }
}
