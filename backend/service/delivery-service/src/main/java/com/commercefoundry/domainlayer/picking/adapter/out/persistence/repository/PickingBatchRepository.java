package com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingBatchRepository extends JpaRepository<PickingBatchEntity, Long> {
}
