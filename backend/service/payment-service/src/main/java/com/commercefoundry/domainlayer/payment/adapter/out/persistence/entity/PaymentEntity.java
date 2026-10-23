package com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 시도 — 시도 하나에 한 행 ({@code payment-guide.md} §5). 취소 이력은 취소 이슈에서 {@code payment_cancel} 로 따로 둔다.
 *
 * <p>상태는 도메인 전이 → 이 엔티티로 매핑 → {@code saveAndFlush}(merge) 로 바꾼다 — JPQL 로 상태를 바꾸지 않는다. {@code @Version} 이 동시 confirm · 재조회 · 웹훅 중
 * 하나만 통과시킨다. 버전이 {@code Long}(wrapper)이라 새 행은 버전이 null 이고 Spring Data 가 그것으로 새 행을 판정해 {@code persist} 한다 —
 * 아이디를 직접 넣어도 {@code Persistable} 이 필요 없다 ({@code coding-conventions.md} §9-1, product {@code StockReservationEntity} 선례).
 *
 * <p>{@code active_order_id} 는 FAILED · ABORTED · LATE_APPROVED 면 null 이고 그 밖은 {@code order_id} 다({@link Payment#activeOrderId()}). 유니크 키가 주문당
 * 활성 결제 시도를 하나로 막고, 실패 · 중단된 주문은 새 시도를 만들 수 있다(PostgreSQL 유니크 키는 null 을 겹치게 둔다). {@code payment_key} 유니크 키도 승인 전 행의 null 을
 * 겹치게 둔다.
 *
 * <p>금액은 {@code total_amount} + {@code total_currency} 짝이다 ({@code coding-conventions.md} §9-3).
 *
 * <p>check 제약은 도메인 불변식({@code Payment} 생성자) 중 행 하나로 볼 수 있는 것을 DB 에서도 막는다 — 잘못된 코드 · 손으로 고친 데이터가 매퍼에서 읽다 실패하는
 * 행을 남기지 않게. 3값 논리에서 {@code null = x} 는 거짓이 아니라 unknown 이라 check 를 통과하므로, 값을 비교하는 항에는 {@code is not null} 을 함께 건다.
 * 취소 상태(PARTIAL_CANCELED · CANCELED)는 아직 닿지 않지만 승인된 결제(DONE)와 같은 칸을 요구하게 미리 넣었다 — 취소 이슈가 DONE 행을 그대로 전이시키면 통과한다.
 * LATE_APPROVED 를 취소할 때는 활성 슬롯 항({@code ck_payment_active_order_id_status})을 취소 이슈에서 다시 본다 — 지금 CANCELED 는 슬롯을 잡는 상태다.
 */
@Entity
@Table(
    name = "payment",
    comment = "결제 시도 (시도 하나에 한 행, 실패 뒤 재시도는 새 행)",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_pg_order_id", columnNames = "pg_order_id"),
        @UniqueConstraint(name = "uk_payment_payment_key", columnNames = "payment_key"),
        @UniqueConstraint(name = "uk_payment_active_order_id", columnNames = "active_order_id")
    },
    indexes = {
        @Index(name = "idx_payment_order_id", columnList = "order_id"),
        @Index(name = "idx_payment_member_id_id", columnList = "member_id, id"),
        @Index(name = "idx_payment_status_order_synced_id", columnList = "status, order_synced, id")
    },
    check = {
        @CheckConstraint(name = "ck_payment_total_amount_positive", constraint = "total_amount > 0"),
        @CheckConstraint(name = "ck_payment_pg_order_id_length", constraint = "length(pg_order_id) >= 6"),
        @CheckConstraint(
            name = "ck_payment_active_order_id_status",
            constraint = "(status in ('FAILED','ABORTED','LATE_APPROVED') and active_order_id is null)"
                + " or (status not in ('FAILED','ABORTED','LATE_APPROVED') and active_order_id is not null and active_order_id = order_id)"
        ),
        @CheckConstraint(name = "ck_payment_approval_started_at_key", constraint = "approval_started_at is null or payment_key is not null"),
        @CheckConstraint(
            name = "ck_payment_in_progress_fields",
            constraint = "status not in ('IN_PROGRESS','UNKNOWN') or (payment_key is not null and approval_started_at is not null)"
        ),
        @CheckConstraint(
            name = "ck_payment_approved_fields",
            constraint = "(status in ('DONE','LATE_APPROVED','PARTIAL_CANCELED','CANCELED')"
                + " and payment_key is not null and method is not null and approved_at is not null)"
                + " or (status not in ('DONE','LATE_APPROVED','PARTIAL_CANCELED','CANCELED')"
                + " and method is null and approved_at is null and easy_pay_provider is null and pg_status is null)"
        ),
        @CheckConstraint(
            name = "ck_payment_failed_fields",
            constraint = "(failure_message is null or failure_code is not null)"
                + " and ((status = 'FAILED' and failure_code is not null) or status = 'LATE_APPROVED'"
                + " or (status not in ('FAILED','LATE_APPROVED') and failure_code is null))"
        ),
        @CheckConstraint(
            name = "ck_payment_unknown_fields",
            constraint = "(status = 'UNKNOWN' and unknown_reason is not null)"
                + " or (status in ('READY','IN_PROGRESS','ABORTED','WAITING_FOR_DEPOSIT') and unknown_reason is null)"
                + " or status in ('DONE','FAILED','LATE_APPROVED','PARTIAL_CANCELED','CANCELED')"
        ),
        @CheckConstraint(
            name = "ck_payment_abort_reason_status",
            constraint = "(status = 'ABORTED' and abort_reason is not null) or status = 'LATE_APPROVED'"
                + " or (status not in ('ABORTED','LATE_APPROVED') and abort_reason is null)"
        ),
        @CheckConstraint(
            name = "ck_payment_late_approved_origin",
            constraint = "status <> 'LATE_APPROVED' or (failure_code is not null and abort_reason is null) or (failure_code is null and abort_reason is not null)"
        ),
        @CheckConstraint(
            name = "ck_payment_order_synced_status",
            constraint = "order_synced = false or status in ('DONE','PARTIAL_CANCELED','CANCELED')"
        ),
        @CheckConstraint(
            name = "ck_payment_order_sync_failure_code_status",
            constraint = "order_sync_failure_code is null or (order_synced = false and status in ('DONE','PARTIAL_CANCELED','CANCELED'))"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "결제 시도 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (외부 FK: order-service orders.id)")
    private Long orderId;

    @Column(name = "member_id", nullable = false, comment = "결제한 회원 아이디 (외부 FK: auth-service member.id)")
    private Long memberId;

    @Column(name = "pg_order_id", nullable = false, length = Payment.PG_ORDER_ID_MAX_LENGTH,
        comment = "PG 에 보낸 주문번호 (6~64자, 영문 · 숫자 · - · _). 결제 시도 아이디 문자열이다 — 승인 · 취소된 번호는 PG 가 재사용을 거절한다")
    private String pgOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 20, comment = "PG (TOSS)")
    private PaymentGateway gateway;

    @Column(name = "total_amount", nullable = false, comment = "결제 금액 (0 초과, 최소 화폐 단위). order-service 가 계산한 주문 금액")
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "total_currency", nullable = false, length = 3, comment = "결제 금액 통화")
    private CurrencyCode totalCurrency;

    @Column(name = "order_name", nullable = false, length = Payment.ORDER_NAME_MAX_LENGTH, comment = "PG 결제창 주문명 (1~100자)")
    private String orderName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20,
        comment = "결제 상태 (READY / IN_PROGRESS / DONE / FAILED / UNKNOWN / ABORTED / LATE_APPROVED / PARTIAL_CANCELED / CANCELED / WAITING_FOR_DEPOSIT)")
    private PaymentStatus status;

    @Column(name = "payment_key", length = Payment.PAYMENT_KEY_MAX_LENGTH, comment = "PG 결제키 (승인 시작부터). 승인 시작 전이면 null")
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 30, comment = "승인된 결제수단. 승인 전이면 null")
    private PaymentMethod method;

    @Column(name = "easy_pay_provider", length = Payment.EASY_PAY_PROVIDER_MAX_LENGTH, comment = "간편결제 제공자 (예: 카카오페이). 간편결제가 아니면 null")
    private String easyPayProvider;

    @Column(name = "pg_status", length = Payment.PG_STATUS_MAX_LENGTH, comment = "승인 때 PG 가 준 원본 상태 문자열 (대사 · 장애 분석용)")
    private String pgStatus;

    @Column(name = "approval_started_at", comment = "마지막 승인 요청 시작 시각 — IN_PROGRESS 로 남은 행을 복구하는 단서. 되돌린 READY 에도 흔적으로 남는다")
    private Instant approvalStartedAt;

    @Column(name = "approved_at", comment = "PG 승인 시각")
    private Instant approvedAt;

    @Column(name = "failure_code", length = Payment.FAILURE_CODE_MAX_LENGTH, comment = "PG 거절 코드 (FAILED 와 FAILED 에서 온 LATE_APPROVED 만)")
    private String failureCode;

    @Column(name = "failure_message", length = Payment.FAILURE_MESSAGE_MAX_LENGTH, comment = "PG 거절 메시지 (거절 코드가 있을 때만, 510자에서 자른다)")
    private String failureMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "unknown_reason", length = 40, comment = "결과 미확정(UNKNOWN)으로 둔 이유. 재조회로 확정한 뒤(DONE · FAILED 계열)에도 이력으로 남는다")
    private PaymentUnknownReason unknownReason;

    @Column(name = "order_synced", nullable = false, comment = "결제 완료를 order-service 에 반영했는가. DONE 계열만 true — false 인 DONE 은 재전송 대상 (LATE_APPROVED 는 반영하지 않는다)")
    private boolean orderSynced;

    @Column(name = "order_sync_failure_code", length = Payment.ORDER_SYNC_FAILURE_CODE_MAX_LENGTH,
        comment = "주문 반영 종결 실패 시 order-service 오류 코드. 반영 전 · 반영 성공이면 null")
    private String orderSyncFailureCode;

    @Column(name = "active_order_id", comment = "활성 결제 시도의 주문 아이디 — FAILED · ABORTED · LATE_APPROVED 면 null, 그 밖은 order_id (주문당 활성 결제 시도 1건)")
    private Long activeOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "abort_reason", length = 30, comment = "결제 시도 중단 사유 (ABORTED 와 ABORTED 에서 온 LATE_APPROVED 만)")
    private PaymentAbortReason abortReason;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
