package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 재고 선점 헤더. 상태를 바꾸는 JPQL 을 두지 않는다 — 도메인 전이 뒤 {@code save}(merge, {@code @Version} 검사)로만 바꾼다.
 */
public interface StockReservationRepository extends JpaRepository<StockReservationEntity, Long> {

    /** 주문의 활성 선점 — {@code uk_stock_reservation_active_order_id} 로 찾는다. RELEASED 는 이 값이 null 이라 걸리지 않는다. */
    Optional<StockReservationEntity> findByActiveOrderId(Long activeOrderId);

    /** 만료 대상 아이디. {@code idx_stock_reservation_status_expires_at} 를 탄다. 오래 만료된 것부터 푼다. */
    @Query("""
        select r.id
          from StockReservationEntity r
         where r.status = :status and r.expiresAt <= :now
         order by r.expiresAt, r.id
        """)
    List<Long> findIdsByStatusAndExpiresAtUpTo(StockReservationStatus status, Instant now, Limit limit);
}
