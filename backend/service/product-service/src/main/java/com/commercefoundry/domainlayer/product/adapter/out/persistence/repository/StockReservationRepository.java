package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.application.port.out.query.ExpiredStockReservationQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * 재고 선점 헤더. 상태를 바꾸는 JPQL 을 두지 않는다 — 도메인 전이 뒤 {@code save}(merge, {@code @Version} 검사)로만 바꾼다.
 *
 * <p>만료 대상 조회는 {@code idx_stock_reservation_status_expires_at_id (status, expires_at, id)} 를 탄다. 오래 만료된 것부터 {@code (expires_at, id)}
 * 순으로 읽고, 다음 배치는 마지막 건 다음부터 읽는다(keyset) — 같은 실행 안에서 실패한 건을 다시 읽어 배치를 막지 않는다.
 */
public interface StockReservationRepository extends JpaRepository<StockReservationEntity, Long> {

    /** 주문의 활성 선점 — {@code uk_stock_reservation_active_order_id} 로 찾는다. RELEASED 는 이 값이 null 이라 걸리지 않는다. */
    Optional<StockReservationEntity> findByActiveOrderId(Long activeOrderId);

    /**
     * {@link #findByIdForUpdate} 의 잠금 대기 상한 (ms). 호출자(order-service) Feign read timeout(3000ms)보다 짧게 둔다 — 길면 호출자가 먼저 끊고
     * 재시도해, 앞 요청이 아직 잠금을 기다리는 동안 같은 요청이 또 줄을 선다. 넘으면 어댑터가 재시도 가능한 409 {@code STOCK_RESERVATION_LOCK_TIMEOUT} 로 바꾼다.
     */
    String LOCK_TIMEOUT_MILLIS = "2000";

    /**
     * 선점 헤더 한 행을 비관적 쓰기 잠금으로 읽는다 — 결제 후 재고 복원 전용이다. PostgreSQL 에서는 {@code for no key update}(Hibernate 7) 다 —
     * 같은 잠금 · 헤더 UPDATE(해제 · 만료 · 확정)와만 충돌하고, FK 검사의 KEY SHARE 는 막지 않는다. 잠금 대기는 {@link #LOCK_TIMEOUT_MILLIS} 까지다.
     *
     * <p>복원은 선점 상태를 바꾸지 않아 {@code @Version} 이 경합을 잡지 못한다. 같은 선점에 서로 다른 클레임이 동시에 오면 각자 "이미 복원한 수량" 을
     * 읽고 함께 상한을 넘길 수 있어, 헤더 행 잠금으로 같은 선점의 복원을 줄 세운다. 잠금 순서는 선점 헤더 → 재고 행(SKU 오름차순)으로
     * 해제 · 만료(헤더 UPDATE → 재고 오름차순)와 같아 교착이 없다. 네이티브가 아닌 정적 JPQL + {@link Lock} 이다 ({@code coding-conventions.md} §9-5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Query("select r from StockReservationEntity r where r.id = :id")
    Optional<StockReservationEntity> findByIdForUpdate(Long id);

    /** 만료 대상의 첫 배치. */
    @Query("""
        select new com.commercefoundry.domainlayer.product.application.port.out.query.ExpiredStockReservationQueryResult(r.id, r.expiresAt)
          from StockReservationEntity r
         where r.status = :status and r.expiresAt <= :now
         order by r.expiresAt, r.id
        """)
    List<ExpiredStockReservationQueryResult> findExpired(StockReservationStatus status, Instant now, Limit limit);

    /** 만료 대상의 다음 배치 — {@code (afterExpiresAt, afterId)} 다음부터. 행 값 비교 대신 OR 로 풀어 H2 · PostgreSQL 모두 같은 뜻이다. */
    @Query("""
        select new com.commercefoundry.domainlayer.product.application.port.out.query.ExpiredStockReservationQueryResult(r.id, r.expiresAt)
          from StockReservationEntity r
         where r.status = :status and r.expiresAt <= :now
           and (r.expiresAt > :afterExpiresAt or (r.expiresAt = :afterExpiresAt and r.id > :afterId))
         order by r.expiresAt, r.id
        """)
    List<ExpiredStockReservationQueryResult> findExpiredAfter(
        StockReservationStatus status, Instant now, Instant afterExpiresAt, Long afterId, Limit limit
    );
}
