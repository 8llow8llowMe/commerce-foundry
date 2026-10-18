package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.application.port.out.query.ExpiredStockReservationQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 재고 선점 헤더. 상태를 바꾸는 JPQL 을 두지 않는다 — 도메인 전이 뒤 {@code save}(merge, {@code @Version} 검사)로만 바꾼다.
 *
 * <p>만료 대상 조회는 {@code idx_stock_reservation_status_expires_at_id (status, expires_at, id)} 를 탄다. 오래 만료된 것부터 {@code (expires_at, id)}
 * 순으로 읽고, 다음 배치는 마지막 건 다음부터 읽는다(keyset) — 같은 실행 안에서 실패한 건을 다시 읽어 배치를 막지 않는다.
 */
public interface StockReservationRepository extends JpaRepository<StockReservationEntity, Long> {

    /** 주문의 활성 선점 — {@code uk_stock_reservation_active_order_id} 로 찾는다. RELEASED 는 이 값이 null 이라 걸리지 않는다. */
    Optional<StockReservationEntity> findByActiveOrderId(Long activeOrderId);

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
