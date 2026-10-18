package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationItemEntity;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockReservationItemRepository extends JpaRepository<StockReservationItemEntity, Long> {

    /** 선점 하나의 항목 — {@code uk_stock_reservation_item_reservation_id_sku_id} 를 타고 SKU 아이디 오름차순이다. */
    List<StockReservationItemEntity> findAllByReservationIdOrderBySkuIdAsc(Long reservationId);

    /**
     * SKU 하나의 {@code status} 선점 중 재고를 차감한 항목의 수량 합. 없으면 null 이다.
     *
     * <p>항목 테이블은 주문마다 쌓여 계속 커지고 {@code sku_id} 단독 인덱스가 없다. 그래서 적은 쪽(RESERVED 헤더 —
     * {@code idx_stock_reservation_status_expires_at_id} 의 status 접두)에서 출발해 {@code (reservation_id, sku_id)} 유니크 키로 항목을 찾도록
     * in 서브쿼리로 묶는다. 연관관계가 없어 엔티티 join 대신 서브쿼리다.
     */
    @Query("""
        select sum(i.quantity)
          from StockReservationItemEntity i
         where i.skuId = :skuId and i.deducted = true
           and i.reservationId in (select r.id from StockReservationEntity r where r.status = :status)
        """)
    Long sumDeductedQuantityBySkuIdAndReservationStatus(Long skuId, StockReservationStatus status);
}
