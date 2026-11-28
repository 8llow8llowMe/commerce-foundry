package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimEntity;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderItemClaimQuantityQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderItemQuantityQueryResult;
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

    /** 한 주문의 클레임 — {@code uk_claim_order_id_request_key} 의 앞 열(order_id)을 탄다 (#609). */
    List<ClaimEntity> findAllByOrderIdOrderByIdDesc(long orderId);

    /**
     * 한 주문 · 한 유형에서 {@code statuses} 상태인 클레임의 항목별 수량 합 — 상태 집합은 도메인이 준다(반품은 {@link Claim#returnQuantityReservingStatuses()}, #17 B1,
     * 취소는 {@link Claim#cancellationQuantityReservingStatuses()}, #684).
     */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.OrderItemQuantityQueryResult(i.orderItemId, sum(i.quantity))
          from ClaimEntity c, ClaimItemEntity i
         where c.id = i.claimId and c.orderId = :orderId and c.type = :type and c.status in :statuses
         group by i.orderItemId
        """)
    List<OrderItemQuantityQueryResult> sumQuantities(long orderId, Claim.Type type, Collection<Claim.Status> statuses);

    /**
     * 여러 주문 · 한 유형에서 {@code statuses} 상태인 클레임의 주문별 · 항목별 수량 합 (#684) — {@link #sumQuantities} 를 주문 아이디 IN 묶음으로 한 번에 읽는다(N+1 방지).
     * {@code uk_claim_order_id_request_key}(order_id, …) 와 {@code uk_claim_item_claim_id_order_item_id}(claim_id, …) 의 앞 열을 탄다. 묶음 크기는 부르는 쪽이 정한다.
     */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.OrderItemClaimQuantityQueryResult(c.orderId, i.orderItemId, sum(i.quantity))
          from ClaimEntity c, ClaimItemEntity i
         where c.id = i.claimId and c.orderId in :orderIds and c.type = :type and c.status in :statuses
         group by c.orderId, i.orderItemId
        """)
    List<OrderItemClaimQuantityQueryResult> sumQuantitiesByOrderIds(Collection<Long> orderIds, Claim.Type type, Collection<Claim.Status> statuses);

    /**
     * 한 유형의 재시도 대상 (#16 PR-2, 유형 인자화 #17 B2a) — 유형이 하나라 {@code idx_claim_type_next_attempt_at_id}(type, next_attempt_at, id) 를
     * 정렬된 범위 스캔으로 읽고 {@code limit} 건에서 멈춘다. 유형을 OR 로 섞으면 BitmapOr 뒤 전체 정렬이 되어 조기 종료를 잃으므로 유형마다 따로 부르고
     * 어댑터가 병합한다. 종결 클레임은 {@code next_attempt_at} 이 null 이라 걸리지 않는다(상태 조건은 이중 안전장치). 상태 집합은 도메인이 준다({@link Claim.Type#awaitingAttemptStatuses()}).
     */
    @Query("""
        select c from ClaimEntity c
         where c.type = :type and c.status in :statuses and c.nextAttemptAt <= :now
         order by c.nextAttemptAt asc, c.id asc
        """)
    List<ClaimEntity> findRetryTargets(Claim.Type type, Collection<Claim.Status> statuses, Instant now, Limit limit);
}
