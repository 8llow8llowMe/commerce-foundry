package com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingOrderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingOrderRepository extends JpaRepository<PickingOrderEntity, Long> {

    /** {@code uk_picking_order_batch_id_order_id} 로 묶음의 대상 · 제외 주문을 읽는다. */
    List<PickingOrderEntity> findAllByBatchIdOrderByOrderIdAsc(long batchId);
}
