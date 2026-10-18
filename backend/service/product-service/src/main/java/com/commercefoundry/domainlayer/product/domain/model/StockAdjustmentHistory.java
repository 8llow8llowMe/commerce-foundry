package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;

/**
 * 어드민 재고 조정 이력 한 건 (append-only — 고치거나 지우지 않는다). 조정 요청 한 번에 한 건이다.
 *
 * @param delta 수량 변화 ({@code afterQuantity - beforeQuantity}). 관리 여부만 바꾼 조정은 0 이다
 * @param memo 없으면 null
 * @param adminMemberId 조정한 어드민 회원 아이디 (access 토큰의 memberId)
 */
public record StockAdjustmentHistory(
    long id, long skuId, long beforeQuantity, long afterQuantity, long delta,
    boolean beforeStockManaged, boolean afterStockManaged, StockAdjustmentReason reason, String memo, long adminMemberId
) {

    public StockAdjustmentHistory {
        Objects.requireNonNull(reason, "reason");
        if (delta != afterQuantity - beforeQuantity) {
            throw new IllegalArgumentException("delta 는 afterQuantity - beforeQuantity 여야 합니다: " + delta);
        }
    }

    /** 조정 전후 재고에서 이력을 만든다. 수량 변화는 전후 차이로 계산한다. */
    public static StockAdjustmentHistory of(long id, Stock before, Stock after, StockAdjustmentReason reason, String memo, long adminMemberId) {
        if (before.skuId() != after.skuId()) {
            throw new IllegalArgumentException("조정 전후 SKU 가 다릅니다: " + before.skuId() + " / " + after.skuId());
        }
        return new StockAdjustmentHistory(
            id, after.skuId(), before.availableQuantity(), after.availableQuantity(), after.availableQuantity() - before.availableQuantity(),
            before.stockManaged(), after.stockManaged(), reason, memo, adminMemberId
        );
    }
}
