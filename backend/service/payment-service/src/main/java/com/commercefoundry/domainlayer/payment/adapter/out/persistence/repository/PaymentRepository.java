package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> findLockedById(@Param("id") Long id);

    /** {@code uk_payment_pg_order_id} 로 찾는다 — 웹훅 · successUrl 이 PG 주문번호로 결제 시도를 가리킨다. */
    Optional<PaymentEntity> findByPgOrderId(String pgOrderId);

    /** {@code uk_payment_active_order_id} 로 찾는다 — 주문당 활성(FAILED · ABORTED 아닌) 결제 시도는 많아야 하나다. */
    Optional<PaymentEntity> findByActiveOrderId(Long activeOrderId);

    /** {@code uk_payment_member_approval_key} 로 찾는다 — 회원이 같은 Idempotency-Key 로 승인을 시작한 결제 시도는 많아야 하나다. */
    Optional<PaymentEntity> findByMemberIdAndApprovalIdempotencyKey(Long memberId, String approvalIdempotencyKey);

    /**
     * 승인 뒤 {@code approvedBefore} 가 지나도록 주문 회신을 받지 않은 결제 수 — 지표 (#308 PR-C). DONE · PARTIAL_CANCELED 만 센다 — 전액 취소(CANCELED)된 결제는
     * order 가 회신하지 않는 것이 정상이다(order {@code OrderPaymentProcessor} 의 전액 취소 분기). 늦은 승인은 이 상태가 될 수 없다(전액 취소만 받는다).
     * {@code idx_payment_status_order_synced_id} 의 앞 두 칸({@code status, order_synced})을 탄다. 관측 쿼리라 2초 상한을 둔다.
     */
    @Query("""
        select count(p) from PaymentEntity p
         where p.status in :statuses and p.orderSynced = false and p.orderSyncFailureCode is null and p.approvedAt <= :approvedBefore
        """)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long countAwaitingOrderReply(Collection<PaymentStatus> statuses, Instant approvedBefore);

    /**
     * 주문이 거절했는데 돈이 남아 있는 결제 수 — 지표 (#308 PR-C). 전액 취소(CANCELED)되면 빠진다. 거절 코드는 {@code order_synced = false} 일 때만 있다(check 제약) —
     * 같은 결과지만 조건을 적어 {@code idx_payment_status_order_synced_id} 의 앞 두 칸({@code status, order_synced})을 타게 한다. 관측 쿼리라 2초 상한을 둔다.
     */
    @Query("select count(p) from PaymentEntity p where p.status in :statuses and p.orderSynced = false and p.orderSyncFailureCode is not null")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long countOrderRejected(Collection<PaymentStatus> statuses);
}
