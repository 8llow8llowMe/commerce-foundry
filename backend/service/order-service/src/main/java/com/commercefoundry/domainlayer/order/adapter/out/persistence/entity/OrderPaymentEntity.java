package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
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
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 결제 반영 기록 (#308, 도메인 {@code OrderPayment}) — 결제당 한 행({@code uk_order_payment_payment_id}), 주문당 결제 완료 하나({@code uk_order_payment_paid_order_id}).
 * {@code paid_order_id} 는 결과가 결제 완료일 때만 주문 아이디이고 아니면 null 이라 거절 기록은 유니크에 걸리지 않는다(PostgreSQL 유니크는 null 을 겹쳐 둔다).
 * 주문의 결제 기록은 {@code idx_order_payment_order_id_id (order_id, id)} 로 읽는다 — PR-C 의 확정 뒤 취소 복원 대상 찾기 · 어드민 주문 상세의 결제 반영 이력용이다.
 *
 * <p>check 는 도메인 불변식 중 이 행 안에서 볼 수 있는 것을 막는다 — 결제 완료 ⇔ {@code paid_order_id = order_id}, 결제 완료면 확정 선점 · 승인 시각 있음,
 * 확정 선점은 PAID · ORDER_CANCELED 또는 원승인 시각 · 복원 클레임이 있는 NOT_DONE에만, 복원 클레임은 확정 선점이 있는 두 거절 결과에만,
 * 복원 시각은 복원 클레임이 있을 때만. {@code result} 값 목록 check 는 Hibernate 가 이름 없이 만든다 — 값을 더하면 운영 DDL 로 지우고 다시 만든다.
 *
 * <p>행은 추가만 한다. {@code restoration_claim_id} 는 확정 뒤 취소 거절 기록을 INSERT 할 때 함께 넣는다(#308 PR-C1 — 나중에 채우지 않는다).
 * 바뀌는 칸은 {@code restored_at} 하나이고 merge 가 아니라 조건부 UPDATE({@code ... where id = :id and restoration_claim_id is not null and restored_at is null})
 * 로 채운다 — 두 처리가 같은 행을 읽고 덮어쓰는 lost update 를 막는다.
 */
@Entity
@Table(
    name = "order_payment",
    comment = "결제 반영 기록 (payment-approved 이벤트 + 재조회 결과, 결제당 1행)",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_payment_payment_id", columnNames = "payment_id"),
        @UniqueConstraint(name = "uk_order_payment_paid_order_id", columnNames = "paid_order_id")
    },
    indexes = @Index(name = "idx_order_payment_order_id_id", columnList = "order_id, id"),
    check = {
        @CheckConstraint(name = "ck_order_payment_total_amount_positive", constraint = "total_amount > 0"),
        @CheckConstraint(
            name = "ck_order_payment_paid_order_id_consistent",
            constraint = "(result = 'PAID' and paid_order_id is not null and paid_order_id = order_id) or (result <> 'PAID' and paid_order_id is null)"
        ),
        @CheckConstraint(
            name = "ck_order_payment_paid_requires_confirmation",
            constraint = "result <> 'PAID' or (stock_reservation_id is not null and approved_at is not null)"
        ),
        @CheckConstraint(
            name = "ck_order_payment_reservation_scope",
            constraint = "stock_reservation_id is null or result in ('PAID', 'REJECTED_ORDER_CANCELED')"
                + " or (result = 'REJECTED_PAYMENT_NOT_DONE' and restoration_claim_id is not null and approved_at is not null)"
        ),
        @CheckConstraint(
            name = "ck_order_payment_restoration_consistent",
            constraint = "(restoration_claim_id is null and restored_at is null)"
                + " or (restoration_claim_id is not null and result in ('REJECTED_ORDER_CANCELED', 'REJECTED_PAYMENT_NOT_DONE') and stock_reservation_id is not null)"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderPaymentEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "결제 반영 기록 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (논리 FK: orders.id, 제약 없음) — 이벤트가 가리킨 주문. 주문 없음 거절이면 없는 아이디다")
    private Long orderId;

    @Column(name = "payment_id", nullable = false, comment = "결제 아이디 (외부 FK: payment-service payment.id)")
    private Long paymentId;

    @Column(name = "member_id", nullable = false, comment = "결제한 회원 아이디 (외부 FK: auth-service member.id, payment 재조회 값)")
    private Long memberId;

    @Column(name = "total_amount", nullable = false, comment = "결제 금액 — 통화의 최소 단위 정수 (payment 재조회 값)")
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "total_currency", nullable = false, length = 3, comment = "결제 금액 통화")
    private CurrencyCode totalCurrency;

    @Column(name = "approved_at", comment = "결제 승인 시각 (payment 재조회 값). 결제 완료면 필수, 거절이면 null 일 수 있다")
    private Instant approvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 40, comment = "반영 결과 (PAID / REJECTED_* — 거절 사유는 order-payment-rejected 회신 reason 과 같다)")
    private OrderPayment.Result result;

    @Column(name = "paid_order_id", comment = "결제 완료면 order_id, 아니면 null — 주문당 결제 완료 1건 유니크")
    private Long paidOrderId;

    @Column(name = "stock_reservation_id", comment = "확정 선점 아이디 (외부 FK: product-service stock_reservation.id). PAID · ORDER_CANCELED 또는 원승인 시각과 복원 클레임이 있는 NOT_DONE")
    private Long stockReservationId;

    @Column(name = "event_id", nullable = false, comment = "반영한 payment-approved 이벤트 아이디 (봉투 eventId)")
    private UUID eventId;

    @Column(name = "restoration_claim_id", comment = "확정 뒤 ORDER_CANCELED 또는 NOT_DONE 거절의 재고 복원 클레임 아이디 (product stock-restorations 멱등 키). 없으면 null")
    private Long restorationClaimId;

    @Column(name = "restored_at", comment = "재고 복원 200 을 받은 시각 (#308 PR-C). 복원 전이면 null")
    private Instant restoredAt;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다(아이디를 직접 넣어 merge 의 SELECT 를 피한다). */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
