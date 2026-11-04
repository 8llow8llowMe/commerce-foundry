package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderPaymentEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 결제 반영 기록 (#308). */
public interface OrderPaymentRepository extends JpaRepository<OrderPaymentEntity, Long> {

    /** 유니크 키 {@code uk_order_payment_payment_id} 로 찾는다. */
    Optional<OrderPaymentEntity> findByPaymentId(Long paymentId);

    /** 주문의 결제 완료 기록 — 유니크 키 {@code uk_order_payment_paid_order_id} 로 찾는다. */
    Optional<OrderPaymentEntity> findByPaidOrderId(Long paidOrderId);

    boolean existsByOrderIdAndStockReservationIdAndRestorationClaimIdIsNotNull(Long orderId, Long stockReservationId);

    /** 재고 복원 완료 표시 (#308 PR-C1) — 아직 비어 있을 때만 채운다(PK 한 행). 0행이면 다른 처리가 먼저 채웠거나 복원 클레임이 없다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OrderPaymentEntity p set p.restoredAt = :restoredAt, p.updatedAt = :restoredAt
        where p.id = :id and p.restorationClaimId is not null and p.restoredAt is null
        """)
    int markRestored(Long id, Instant restoredAt);
}
