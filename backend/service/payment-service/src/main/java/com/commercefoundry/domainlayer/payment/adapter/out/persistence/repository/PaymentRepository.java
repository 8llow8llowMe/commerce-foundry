package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    @Query("select coalesce(max(p.id), 0) from PaymentEntity p")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long findReconciliationUpperId();

    @Query("select p.id" + AWAITING_ORDER_REPLY + " and p.id > :afterId and p.id <= :upperId order by p.id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<Long> findReconciliationIds(Collection<PaymentStatus> statuses, Instant approvedBefore, long afterId, long upperId, Pageable page);

    @Query("select p.id from PaymentEntity p where p.status in :statuses and p.orderSynced = false"
        + " and p.orderSyncFailureCode is not null and p.id > :afterId and p.id <= :upperId order by p.id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<Long> findRejectedReconciliationIds(Collection<PaymentStatus> statuses, long afterId, long upperId, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> findLockedById(@Param("id") Long id);

    /** {@code uk_payment_pg_order_id} 로 찾는다 — 웹훅 · successUrl 이 PG 주문번호로 결제 시도를 가리킨다. */
    Optional<PaymentEntity> findByPgOrderId(String pgOrderId);

    /** {@code uk_payment_active_order_id} 로 찾는다 — 주문당 활성(FAILED · ABORTED 아닌) 결제 시도는 많아야 하나다. */
    Optional<PaymentEntity> findByActiveOrderId(Long activeOrderId);

    /** {@code uk_payment_member_approval_key} 로 찾는다 — 회원이 같은 Idempotency-Key 로 승인을 시작한 결제 시도는 많아야 하나다. */
    Optional<PaymentEntity> findByMemberIdAndApprovalIdempotencyKey(Long memberId, String approvalIdempotencyKey);
    // count/min은 같은 모집단을 사용하지만 별도 트랜잭션의 독립 관측이다.
    String AWAITING_ORDER_REPLY = " from PaymentEntity p where p.status in :statuses and p.orderSynced = false"
        + " and p.orderSyncFailureCode is null and p.approvedAt < :approvedBefore";

    @Transactional(readOnly = true, timeout = 2)
    @Query("select count(p)" + AWAITING_ORDER_REPLY)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long countAwaitingOrderReply(Collection<PaymentStatus> statuses, Instant approvedBefore);

    @Transactional(readOnly = true, timeout = 2)
    @Query("select min(p.approvedAt)" + AWAITING_ORDER_REPLY)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    Instant findOldestAwaitingOrderReplyApprovedAt(Collection<PaymentStatus> statuses, Instant approvedBefore);

    @Transactional(readOnly = true, timeout = 2)
    @Query("select count(p) from PaymentEntity p where p.status in :statuses and p.orderSynced = false and p.orderSyncFailureCode is not null")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long countOrderRejected(Collection<PaymentStatus> statuses);
}
