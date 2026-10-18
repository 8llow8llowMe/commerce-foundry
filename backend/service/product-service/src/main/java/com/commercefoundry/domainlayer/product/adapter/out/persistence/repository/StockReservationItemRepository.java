package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReservationItemRepository extends JpaRepository<StockReservationItemEntity, Long> {

    /** 선점 하나의 항목 — {@code uk_stock_reservation_item_reservation_id_sku_id} 를 타고 SKU 아이디 오름차순이다. */
    List<StockReservationItemEntity> findAllByReservationIdOrderBySkuIdAsc(Long reservationId);
}
