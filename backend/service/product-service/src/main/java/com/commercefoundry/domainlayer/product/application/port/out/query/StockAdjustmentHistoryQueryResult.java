package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import java.time.Instant;

/**
 * 어드민 재고 조정 이력 한 행과 조정 시각({@code created_at}). 저장에 쓰는 도메인 {@code StockAdjustmentHistory} 에는 시각을 두지 않는다 —
 * 감사 리스너가 INSERT 때 채우는 값이라 조회에서만 나온다.
 *
 * @param memo 없으면 null
 */
public record StockAdjustmentHistoryQueryResult(
    long id, long skuId, long beforeQuantity, long afterQuantity, long delta,
    boolean beforeStockManaged, boolean afterStockManaged, StockAdjustmentReason reason, String memo, long adminMemberId, Instant createdAt
) {
}
