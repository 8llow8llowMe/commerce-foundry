package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.application.port.out.query.StockAdjustmentHistoryQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 재고 조정 이력 한 건.
 *
 * @param delta 수량 변화 ({@code afterQuantity - beforeQuantity}). 관리 여부만 바꾼 조정은 0 이다
 * @param memo 없으면 null
 * @param adminMemberId 조정한 어드민 회원 아이디 (access 토큰의 memberId)
 * @param createdAt 조정 시각
 */
@Builder
public record StockAdjustmentHistoryInfo(
    long id, long skuId, long beforeQuantity, long afterQuantity, long delta,
    boolean beforeStockManaged, boolean afterStockManaged, StockAdjustmentReason reason, String memo, long adminMemberId, Instant createdAt
) {

    public static StockAdjustmentHistoryInfo from(StockAdjustmentHistoryQueryResult history) {
        return StockAdjustmentHistoryInfo.builder()
            .id(history.id())
            .skuId(history.skuId())
            .beforeQuantity(history.beforeQuantity())
            .afterQuantity(history.afterQuantity())
            .delta(history.delta())
            .beforeStockManaged(history.beforeStockManaged())
            .afterStockManaged(history.afterStockManaged())
            .reason(history.reason())
            .memo(history.memo())
            .adminMemberId(history.adminMemberId())
            .createdAt(history.createdAt())
            .build();
    }
}
