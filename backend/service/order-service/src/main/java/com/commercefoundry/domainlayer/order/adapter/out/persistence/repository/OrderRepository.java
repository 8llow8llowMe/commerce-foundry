package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderEntity;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderExpiryCandidateQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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
}
