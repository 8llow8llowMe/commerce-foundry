package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimItemRepository extends JpaRepository<ClaimItemEntity, Long> {
    List<ClaimItemEntity> findAllByClaimIdOrderByOrderItemIdAsc(long claimId);
}
