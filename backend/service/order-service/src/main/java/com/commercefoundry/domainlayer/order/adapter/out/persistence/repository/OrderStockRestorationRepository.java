package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStockRestorationEntity;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 결제 반영 재고 복원 작업 (#582). 바꾸기는 모두 이전 값을 조건으로 건 UPDATE 한 문장이다. */
public interface OrderStockRestorationRepository extends JpaRepository<OrderStockRestorationEntity, Long> {

    boolean existsByClaimId(Long claimId);

    /** 스윕 대상 — {@code idx_order_stock_restoration_status_next_id} 순서 그대로 읽는다. */
    List<OrderStockRestorationEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        OrderStockRestoration.Status status, Instant now, Limit limit);

    /** 스윕 선점 — 읽은 상태 · 다음 시각이 그대로일 때만 시도 횟수 · 다음 시각을 바꾼다. 0행이면 다른 처리가 먼저 집었다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OrderStockRestorationEntity r set r.attempts = :attempts, r.nextAttemptAt = :nextAttemptAt, r.updatedAt = :now
        where r.id = :id and r.status = :status and r.nextAttemptAt = :expectedNextAttemptAt
        """)
    int claim(Long id, OrderStockRestoration.Status status, Instant expectedNextAttemptAt, int attempts, Instant nextAttemptAt, Instant now);

    /** HELD — 선점한 그대로일 때만. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OrderStockRestorationEntity r set r.status = :heldStatus, r.holdCode = :holdCode, r.nextAttemptAt = null, r.updatedAt = :now
        where r.id = :id and r.status = :status and r.nextAttemptAt = :expectedNextAttemptAt
        """)
    int hold(Long id, OrderStockRestoration.Status status, Instant expectedNextAttemptAt, OrderStockRestoration.Status heldStatus, String holdCode,
        Instant now);

    /** 복원 완료 — 클레임의 행이 아직 끝나지 않았을 때만. 0행이면 다른 처리가 먼저 끝냈거나 행이 없다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OrderStockRestorationEntity r set r.status = :restoredStatus, r.restoredAt = :restoredAt, r.nextAttemptAt = null, r.holdCode = null,
        r.updatedAt = :restoredAt
        where r.claimId = :claimId and r.status in :fromStatuses
        """)
    int markRestored(Long claimId, Collection<OrderStockRestoration.Status> fromStatuses, OrderStockRestoration.Status restoredStatus, Instant restoredAt);
}
