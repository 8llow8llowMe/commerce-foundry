package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderEntity;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderConfirmationCandidateQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderExpiryCandidateQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PendingProductQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 주문 헤더. 상태를 바꾸는 JPQL 을 두지 않는다 — 도메인 전이 뒤 merge({@code @Version} 검사)로만 바꾼다.
 *
 * <p>만료 대상 조회는 {@code idx_orders_status_payment_due_at_id (status, payment_due_at, id)} 를 탄다. 오래 지난 것부터 {@code (payment_due_at, id)}
 * 순으로 읽고, 다음 배치는 마지막 건 다음부터 읽는다(keyset). 첫 배치 · 다음 배치를 나눈다 — {@code :after is null or ...} 처럼 null 일 수 있는
 * 파라미터를 한 쿼리에 두면 PostgreSQL 이 파라미터 타입을 정하지 못한다(product {@code StockReservationRepository} 와 같은 모양).
 */
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    Optional<OrderEntity> findByIdAndMemberId(Long id, Long memberId);

    /** 회원의 주문 최신순 한 조각 (#16) — {@code idx_orders_member_id_id} 를 거꾸로 읽는다(아이디가 시각 순인 Snowflake 다). 건수 쿼리가 없다. */
    Slice<OrderEntity> findSliceByMemberIdOrderByIdDesc(Long memberId, Pageable pageable);

    /**
     * 회원의 {@code status} 주문에 담긴 상품별 수량 합 (#426). 주문은 {@code idx_orders_member_id_id}, 항목은 {@code uk_order_item_order_id_line_no} 의
     * 선두 {@code order_id} 로 찾는다 — 결제 대기 주문은 회원당 #231 상한(5건) 안팎이라 읽는 행이 적다.
     */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.PendingProductQuantityQueryResult(i.productId, sum(i.quantity))
          from OrderEntity o, OrderItemEntity i
         where i.orderId = o.id and o.memberId = :memberId and o.status = :status and i.productId in :productIds
         group by i.productId
        """)
    List<PendingProductQuantityQueryResult> sumQuantityByProduct(Long memberId, OrderStatus status, Collection<Long> productIds);
    /** 어드민 목록 — 주문 시각 [from, to) · 최신순 (#17). {@code idx_orders_created_at_id} 범위 스캔(본문 · 건수 모두). */
    Page<OrderEntity> findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(Instant from, Instant to, Pageable pageable);

    /** 어드민 목록 — 상태 · 주문 시각 [from, to) · 최신순 (#17). {@code idx_orders_status_created_at_id} 범위 스캔. */
    Page<OrderEntity> findAllByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
        OrderStatus status, Instant from, Instant to, Pageable pageable
    );

    /** 만료 대상의 첫 배치. */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.OrderExpiryCandidateQueryResult(o.id, o.paymentDueAt)
          from OrderEntity o
         where o.status = :status and o.paymentDueAt <= :now
         order by o.paymentDueAt, o.id
        """)
    List<OrderExpiryCandidateQueryResult> findDue(OrderStatus status, Instant now, Limit limit);

    /**
     * 만료 대상의 다음 배치 — {@code (afterDueAt, afterId)} 다음부터. 행 값 비교 대신 OR 로 풀어 H2 · PostgreSQL 모두 같은 뜻이다.
     * {@code paymentDueAt >= :afterDueAt} 은 OR 조건이 이미 뜻하는 하한을 따로 적은 것이다 — 플래너가 인덱스 range 시작점으로 쓴다(OR 만 있으면 못 쓸 수 있다).
     */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.OrderExpiryCandidateQueryResult(o.id, o.paymentDueAt)
          from OrderEntity o
         where o.status = :status and o.paymentDueAt <= :now
           and o.paymentDueAt >= :afterDueAt
           and (o.paymentDueAt > :afterDueAt or (o.paymentDueAt = :afterDueAt and o.id > :afterId))
         order by o.paymentDueAt, o.id
        """)
    List<OrderExpiryCandidateQueryResult> findDueAfter(OrderStatus status, Instant now, Instant afterDueAt, Long afterId, Limit limit);

    /** 자동 구매 확정 조회는 (deliveredAt, id) 키셋과 기존 인덱스를 사용한다. */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.OrderConfirmationCandidateQueryResult(o.id, o.deliveredAt)
          from OrderEntity o
         where o.status = :status and o.deliveredAt <= :cutoff
         order by o.deliveredAt, o.id
        """)
    List<OrderConfirmationCandidateQueryResult> findConfirmationDue(OrderStatus status, Instant cutoff, Limit limit);

    /** 자동 구매 확정 조회는 (deliveredAt, id) 키셋과 기존 인덱스를 사용한다. */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.OrderConfirmationCandidateQueryResult(o.id, o.deliveredAt)
          from OrderEntity o
         where o.status = :status and o.deliveredAt <= :cutoff and o.deliveredAt >= :afterDeliveredAt
           and (o.deliveredAt > :afterDeliveredAt or (o.deliveredAt = :afterDeliveredAt and o.id > :afterId))
         order by o.deliveredAt, o.id
        """)
    List<OrderConfirmationCandidateQueryResult> findConfirmationDueAfter(OrderStatus status, Instant cutoff, Instant afterDeliveredAt, Long afterId, Limit limit);
}
