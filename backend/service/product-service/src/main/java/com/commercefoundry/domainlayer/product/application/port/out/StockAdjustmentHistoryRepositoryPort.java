package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.StockAdjustmentHistoryQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import org.springframework.data.domain.Page;

/**
 * 어드민 재고 조정 이력. append-only 라 추가와 조회만 있다 — 고치거나 지우는 메서드를 두지 않는다.
 */
public interface StockAdjustmentHistoryRepositoryPort {

    void append(StockAdjustmentHistory history);

    /**
     * SKU 하나의 조정 이력을 아이디(Snowflake — 시각 순) 내림차순, 곧 최신순으로 한 페이지 읽는다. 목록 1번 + 전체 건수 1번이고
     * 둘 다 {@code idx_stock_adjustment_history_sku_id_id} 를 탄다. 마지막 페이지를 넘긴 요청은 빈 목록이고 전체 건수는 그대로다.
     *
     * @param page 0 부터 센다
     */
    Page<StockAdjustmentHistoryQueryResult> findPageBySkuId(long skuId, int page, int size);
}
