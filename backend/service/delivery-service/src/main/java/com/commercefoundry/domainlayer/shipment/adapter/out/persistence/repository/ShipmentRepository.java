package com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, Long> {

    /** 유일 키 {@code uk_shipment_order_id} 를 탄다. */
    Optional<ShipmentEntity> findByOrderId(Long orderId);

    /**
     * 주문에 반영되지 않았고 버전이 {@code version} 인 송장만 지운다 — 확인과 삭제가 한 문장이라, 그 사이 다른 요청이 재시도로 바꿨거나 반영한 송장은 남는다.
     *
     * @return 지운 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShipmentEntity s where s.id = :id and s.version = :version and s.orderSynced = false")
    int deletePending(Long id, Long version);
}
