package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.persistence.entity.BaseEntity;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 결제 반영 재고 복원 작업 (#582, 도메인 {@code OrderStockRestoration}) — 확정 선점 · 클레임마다 한 행({@code uk_..._stock_reservation_id} · {@code uk_..._claim_id}).
 * 스윕은 {@code idx_order_stock_restoration_status_next_attempt_at_id (status, next_attempt_at, id)} 로 다음 시각이 지난 대기 행을 읽는다. 부분 인덱스는 두지 않는다
 * (Hibernate 매핑 · H2 시험 정합).
 *
 * <p>행은 넣은 뒤 조건부 UPDATE 로만 바꾼다(이전 상태 · 다음 시각을 조건으로) — 스윕 인스턴스 둘 · 컨슈머 재전달이 같은 행을 덮어쓰지 않는다.
 * check 는 상태별로 다음 시각 · 복원 시각 · HELD 사유가 맞물리게 막는다. {@code origin} · {@code status} 값 목록 check 는 Hibernate 가 이름 없이 만든다.
 */
@Entity
@Table(
    name = "order_stock_restoration",
    comment = "결제 반영 재고 복원 작업 (확정 선점 1건당 1행, 스윕이 같은 클레임으로 복원)",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_stock_restoration_stock_reservation_id", columnNames = "stock_reservation_id"),
        @UniqueConstraint(name = "uk_order_stock_restoration_claim_id", columnNames = "claim_id")
    },
    indexes = @Index(name = "idx_order_stock_restoration_status_next_attempt_at_id", columnList = "status, next_attempt_at, id"),
    check = {
        @CheckConstraint(name = "ck_order_stock_restoration_attempts_non_negative", constraint = "attempts >= 0"),
        @CheckConstraint(
            name = "ck_order_stock_restoration_state_consistent",
            constraint = "(status = 'RESTORE_PENDING' and next_attempt_at is not null and restored_at is null and hold_code is null)"
                + " or (status = 'RESTORED' and next_attempt_at is null and restored_at is not null and hold_code is null)"
                + " or (status = 'HELD' and next_attempt_at is null and restored_at is null and hold_code is not null)"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderStockRestorationEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "복원 작업 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (FK: orders.id)")
    private Long orderId;

    @Column(name = "payment_id", nullable = false, comment = "결제 아이디 (외부 FK: payment-service payment.id)")
    private Long paymentId;

    @Column(name = "stock_reservation_id", nullable = false, comment = "되돌릴 확정 선점 아이디 (외부 FK: product-service stock_reservation.id)")
    private Long stockReservationId;

    @Column(name = "claim_id", nullable = false, comment = "재고 복원 클레임 아이디 (product stock-restorations 멱등 키, 결제 기록 출처면 order_payment.restoration_claim_id)")
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 30, comment = "복원 의도 출처 (PAYMENT_RECORD)")
    private OrderStockRestoration.Origin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "작업 상태 (RESTORE_PENDING / RESTORED / HELD)")
    private OrderStockRestoration.Status status;

    @Column(name = "hold_code", length = 30, comment = "HELD 사유 (상품 오류 코드 또는 OWNERSHIP_MISMATCH). HELD 가 아니면 null")
    private String holdCode;

    @Column(name = "attempts", nullable = false, comment = "스윕이 선점한 횟수")
    private int attempts;

    @Column(name = "next_attempt_at", comment = "스윕이 다음에 집을 시각. 대기 상태에서만 있다")
    private Instant nextAttemptAt;

    @Column(name = "restored_at", comment = "재고 복원 200 을 받은 시각. RESTORED 에서만 있다")
    private Instant restoredAt;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다(아이디를 직접 넣어 merge 의 SELECT 를 피한다). */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
