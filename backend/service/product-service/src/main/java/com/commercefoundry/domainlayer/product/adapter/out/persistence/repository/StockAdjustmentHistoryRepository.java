package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAdjustmentHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재고 조정 이력 (append-only). 지금은 저장만 한다 — 어드민 이력 조회는 후속 이슈다.
 */
public interface StockAdjustmentHistoryRepository extends JpaRepository<StockAdjustmentHistoryEntity, Long> {
}
