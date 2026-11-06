package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 클레임 — 반품 접수 · 결제 후 취소 (#16 PR-2). 도메인 {@code Claim} 생성자와 같은 규칙을 check 로 둔다.
 *
 * <ul>
 *   <li>{@code ck_claim_cancellation_payment} — 취소 클레임은 양수 결제 아이디 · 0보다 큰 환불액이 있다</li>
 *   <li>{@code ck_claim_payment_cancellation_only} — 결제 아이디는 취소 클레임에만 있다</li>
 *   <li>{@code ck_claim_cancellation_next_attempt} — 취소 클레임의 다음 시도 시각은 대기 상태(REQUESTED · REFUNDED)에만 있다</li>
 *   <li>{@code ck_claim_cancellation_failure_code} — 취소 클레임의 실패 코드는 종결 실패(REFUND_FAILED · RESTORATION_FAILED)에만 있다</li>
 * </ul>
 *
 * <p>재시도 스케줄러는 {@code idx_claim_type_next_attempt_at_id} 를
 * {@code type = CANCELLATION and next_attempt_at <= now order by next_attempt_at, id} 로 탄다 — 다음 시도 시각은 대기 상태에만 있어 상태 열을 인덱스에 두지 않는다.
 */
@Entity
@Table(name = "claim", comment = "주문 클레임 접수",
    uniqueConstraints = @UniqueConstraint(name = "uk_claim_order_id_request_key", columnNames = {"order_id", "request_key"}),
    indexes = @Index(name = "idx_claim_type_next_attempt_at_id", columnList = "type, next_attempt_at, id"),
    check = {
        @CheckConstraint(name = "ck_claim_identifiers_positive", constraint = "id > 0 and order_id > 0 and version >= 0"),
        @CheckConstraint(name = "ck_claim_expected_refund_pair", constraint = "(expected_refund_amount is null and expected_refund_currency is null) or "
            + "(expected_refund_amount is not null and expected_refund_currency is not null and expected_refund_amount >= 0)"),
        @CheckConstraint(name = "ck_claim_attempt_count_non_negative", constraint = "attempt_count >= 0"),
        @CheckConstraint(name = "ck_claim_cancellation_payment", constraint = "type <> 'CANCELLATION' or "
            + "(payment_id is not null and payment_id > 0 and expected_refund_amount is not null and expected_refund_amount > 0)"),
        @CheckConstraint(name = "ck_claim_payment_cancellation_only", constraint = "type = 'CANCELLATION' or payment_id is null"),
        @CheckConstraint(name = "ck_claim_cancellation_next_attempt", constraint = "type <> 'CANCELLATION' or "
            + "((status in ('REQUESTED', 'REFUNDED')) = (next_attempt_at is not null))"),
        @CheckConstraint(name = "ck_claim_cancellation_failure_code", constraint = "type <> 'CANCELLATION' or "
            + "((status in ('REFUND_FAILED', 'RESTORATION_FAILED')) = (failure_code is not null))")
    })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ClaimEntity extends BaseEntity {
    @Id
    @Column(name = "id", comment = "클레임 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (FK: orders.id)")
    private Long orderId;

    @Column(name = "request_key", nullable = false, comment = "주문별 멱등 접수 키 (UUID). 전체 취소는 주문 아이디로 만든 결정적 키")
    private UUID requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, comment = "클레임 유형 (RETURN / CANCELLATION)")
    private Claim.Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20,
        comment = "클레임 상태 (REQUESTED / REFUNDED / COMPLETED / REFUND_FAILED / RESTORATION_FAILED)")
    private Claim.Status status;

    @Column(name = "expected_refund_amount", comment = "예상 환불 최소 단위 금액 (미산정이면 null)")
    private Long expectedRefundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_refund_currency", length = 3, comment = "예상 환불 통화 (미산정이면 null)")
    private CurrencyCode expectedRefundCurrency;

    @Column(name = "payment_id", comment = "환불할 결제 아이디 (외부 FK: payment-service payment.id). 취소 클레임에만")
    private Long paymentId;

    @Column(name = "attempt_count", nullable = false, comment = "지금 단계(환불 · 재고 복원)의 미확정 재시도 횟수")
    private int attemptCount;

    @Column(name = "next_attempt_at", comment = "재시도 스케줄러가 다시 집을 시각. 종결이면 null")
    private Instant nextAttemptAt;

    @Column(name = "failure_code", length = Claim.FAILURE_CODE_MAX_LENGTH, comment = "종결 실패 사유 코드 (REFUND_FAILED · RESTORATION_FAILED). 아니면 null")
    private String failureCode;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
