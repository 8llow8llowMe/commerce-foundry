package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockRestorationItemEntity;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuRestoredQuantityQueryResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockRestorationItemRepository extends JpaRepository<StockRestorationItemEntity, Long> {

    /** 복원 하나의 항목 — {@code uk_stock_restoration_item_restoration_id_sku_id} 를 타고 SKU 아이디 오름차순이다. */
    List<StockRestorationItemEntity> findAllByRestorationIdOrderBySkuIdAsc(Long restorationId);

    /**
     * 선점 하나에서 SKU 별로 이미 복원한 수량을 더한다. 복원한 적 없는 SKU 는 행이 없다. 재고를 되돌리지 않은 항목(무제한)도 더한다 — 상한은 선점 수량 기준이다.
     *
     * <p>연관관계가 없어 엔티티 join 대신 in 서브쿼리다 — 헤더는 {@code idx_stock_restoration_reservation_id} 로, 항목은
     * {@code (restoration_id, sku_id)} 유니크 키의 접두로 찾는다. 새 인덱스는 필요 없다. {@code sum(int)} 는 JPQL 에서 {@code Long} 이다.
     */
    @Query("""
        select new com.commercefoundry.domainlayer.product.application.port.out.query.SkuRestoredQuantityQueryResult(i.skuId, sum(i.quantity))
          from StockRestorationItemEntity i
         where i.restorationId in (select r.id from StockRestorationEntity r where r.reservationId = :reservationId)
         group by i.skuId
        """)
    List<SkuRestoredQuantityQueryResult> sumQuantitiesByReservationId(Long reservationId);
}
