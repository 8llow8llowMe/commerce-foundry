package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimEntity;
import com.commercefoundry.domainlayer.order.application.port.out.query.RequestedReturnQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
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

    /** 주어진 주문 중 그 유형 · 상태 클레임이 있는 주문 아이디 (#17) — {@code uk_claim_order_id_request_key} 의 앞 열(order_id)을 탄다. */
    @Query("""
        select distinct c.orderId from ClaimEntity c
         where c.orderId in :orderIds and c.type = :type and c.status = :status
        """)
    List<Long> findOrderIds(Collection<Long> orderIds, Claim.Type type, Claim.Status status);

    /** {@code idx_claim_type_next_attempt_at_id} 를 탄다. 종결 클레임은 {@code next_attempt_at} 이 null 이라 걸리지 않는다(상태 조건은 이중 안전장치). */
    @Query("""
        select c from ClaimEntity c
         where c.type = :type and c.status in :statuses and c.nextAttemptAt <= :now
         order by c.nextAttemptAt asc, c.id asc
        """)
    List<ClaimEntity> findRetryTargets(Claim.Type type, Collection<Claim.Status> statuses, Instant now, Limit limit);
}
