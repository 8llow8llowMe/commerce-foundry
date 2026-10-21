package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderRequestEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRequestRepository extends JpaRepository<OrderRequestEntity, Long> {

    Optional<OrderRequestEntity> findByMemberIdAndIdempotencyKey(Long memberId, UUID idempotencyKey);
}
