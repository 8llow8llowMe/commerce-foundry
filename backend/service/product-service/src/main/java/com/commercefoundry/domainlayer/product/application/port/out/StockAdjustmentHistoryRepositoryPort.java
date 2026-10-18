package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;

/**
 * 어드민 재고 조정 이력. append-only 라 추가만 있다 — 고치거나 지우는 메서드를 두지 않는다.
 */
public interface StockAdjustmentHistoryRepositoryPort {

    void append(StockAdjustmentHistory history);
}
