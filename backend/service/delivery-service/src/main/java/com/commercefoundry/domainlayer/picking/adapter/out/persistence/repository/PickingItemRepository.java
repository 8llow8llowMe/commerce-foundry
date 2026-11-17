package com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingItemRepository extends JpaRepository<PickingItemEntity, Long> {

    /** {@code uk_picking_item_batch_id_order_id_sku_id} 로 읽는다. */
    List<PickingItemEntity> findAllByBatchIdOrderByOrderIdAscSkuIdAsc(long batchId);
}
