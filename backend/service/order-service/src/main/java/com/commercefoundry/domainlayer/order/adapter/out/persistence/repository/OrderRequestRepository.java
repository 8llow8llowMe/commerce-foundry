package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderRequestEntity;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OrderRequestRepository extends JpaRepository<OrderRequestEntity, Long> {
    Optional<OrderRequestEntity> findByMemberIdAndIdempotencyKey(long memberId, UUID idempotencyKey);

    // idx_order_request_member_id_updated_at 범위 조회 — 회원 한 명의 창 안 행만 읽는다. 창은 마지막 활동 시각(삽입 · 인수 · lease 종료 · 완료가 갱신)으로 본다
    long countByMemberIdAndUpdatedAtGreaterThanEqualAndIdNot(long memberId, Instant since, long id);

    // 기한만 갱신해도 버전을 올려 이전 주인의 완료·종료 요청을 거절한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OrderRequestEntity r set r.leaseExpiresAt = :nextLease, r.version = r.version + 1, r.updatedAt = :now
        where r.id = :#{#expected.id} and r.version = :#{#expected.version}
          and r.memberId = :#{#expected.memberId} and r.idempotencyKey = :#{#expected.idempotencyKey}
          and r.requestHash = :#{#expected.requestHash} and r.orderId = :#{#expected.orderId}
          and r.status = :processing and r.leaseExpiresAt <= :now
        """)
    int takeOver(OrderRequest expected, Instant now, Instant nextLease, OrderRequest.Status processing);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OrderRequestEntity r set r.leaseExpiresAt = :now, r.version = r.version + 1, r.updatedAt = :now
        where r.id = :#{#expected.id} and r.version = :#{#expected.version}
          and r.memberId = :#{#expected.memberId} and r.idempotencyKey = :#{#expected.idempotencyKey}
          and r.requestHash = :#{#expected.requestHash} and r.orderId = :#{#expected.orderId}
          and r.status = :processing
        """)
    int endLease(OrderRequest expected, Instant now, OrderRequest.Status processing);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OrderRequestEntity r set r.status = :completed, r.version = r.version + 1, r.updatedAt = :now
        where r.id = :#{#expected.id} and r.version = :#{#expected.version}
          and r.memberId = :#{#expected.memberId} and r.idempotencyKey = :#{#expected.idempotencyKey}
          and r.requestHash = :#{#expected.requestHash} and r.orderId = :#{#expected.orderId}
          and r.status = :processing and r.leaseExpiresAt > :leaseFloor
        """)
    int completeOwned(OrderRequest expected, Instant now, Instant leaseFloor, OrderRequest.Status processing, OrderRequest.Status completed);
}
