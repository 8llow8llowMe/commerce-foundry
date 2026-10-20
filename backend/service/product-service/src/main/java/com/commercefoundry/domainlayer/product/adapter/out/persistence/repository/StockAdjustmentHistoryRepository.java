package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAdjustmentHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재고 조정 이력 (append-only). 저장과 SKU 별 최신순 조회만 있다.
 */
public interface StockAdjustmentHistoryRepository extends JpaRepository<StockAdjustmentHistoryEntity, Long> {

    /**
     * SKU 하나의 이력을 아이디 내림차순(최신순)으로 한 페이지. 목록은 {@code idx_stock_adjustment_history_sku_id_id} 를 {@code sku_id} 등치 +
     * {@code id} 역순 스캔으로 타서 따로 정렬하지 않고 앞에서 자르고, 파생 count 쿼리({@code where sku_id = ?})도 같은 인덱스 접두를 탄다.
     * 정렬은 메서드 이름이 고정한다 — {@code pageable} 에는 정렬을 싣지 않는다.
     */
    Page<StockAdjustmentHistoryEntity> findAllBySkuIdOrderByIdDesc(Long skuId, Pageable pageable);
}
