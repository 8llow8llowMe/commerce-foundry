package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimItemEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimItemRepository extends JpaRepository<ClaimItemEntity, Long> {
    List<ClaimItemEntity> findAllByClaimIdOrderByOrderItemIdAsc(long claimId);

    /** 여러 클레임의 항목을 한 번에 읽는다 — 재시도 대상 목록의 N+1 을 막는다. */
    List<ClaimItemEntity> findAllByClaimIdInOrderByClaimIdAscOrderItemIdAsc(Collection<Long> claimIds);
}
