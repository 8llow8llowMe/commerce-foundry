package com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingCheckEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingCheckRepository extends JpaRepository<PickingCheckEntity, Long> {

    /** {@code uk_picking_check_batch_id_attempt_no} 로 묶음의 검수 기록을 시간순으로 읽는다. */
    List<PickingCheckEntity> findAllByBatchIdOrderByAttemptNoAsc(long batchId);
}
