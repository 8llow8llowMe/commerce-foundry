package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimEntity;
import com.commercefoundry.domainlayer.order.application.port.out.query.RequestedReturnQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<ClaimEntity, Long> {
    Optional<ClaimEntity> findByOrderIdAndRequestKey(long orderId, UUID requestKey);

    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.RequestedReturnQuantityQueryResult(i.orderItemId, sum(i.quantity))
          from ClaimEntity c, ClaimItemEntity i
         where c.id = i.claimId and c.orderId = :orderId and c.type = :type and c.status = :status
         group by i.orderItemId
        """)
    List<RequestedReturnQuantityQueryResult> sumQuantities(long orderId, Claim.Type type, Claim.Status status);
}
