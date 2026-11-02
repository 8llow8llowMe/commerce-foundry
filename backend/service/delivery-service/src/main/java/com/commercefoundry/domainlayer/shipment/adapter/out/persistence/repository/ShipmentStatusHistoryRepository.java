package com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentStatusHistoryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShipmentStatusHistoryRepository extends JpaRepository<ShipmentStatusHistoryEntity, Long> {

    /** 송장의 이력을 시간순(아이디 오름차순 — Snowflake)으로. {@code idx_shipment_status_history_shipment_id_id} 를 탄다. */
    List<ShipmentStatusHistoryEntity> findAllByShipmentIdOrderByIdAsc(Long shipmentId);

    /** 대기 송장을 지울 때만 부른다 — 송장 삭제가 1건일 때 같은 트랜잭션에서. {@code idx_shipment_status_history_shipment_id_id} 를 탄다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShipmentStatusHistoryEntity h where h.shipmentId = :shipmentId")
    int deleteAllByShipmentId(Long shipmentId);
}
