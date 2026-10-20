package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 결제 후 재고 복원 헤더 — 클레임(취소 · 반품) 하나에 한 행. 항목은 {@link StockRestorationItemEntity} 다. 한 번 INSERT 하고 고치지 않는다(append-only).
 *
 * <p>유니크 키 {@code uk_stock_restoration_claim_id} 가 클레임당 복원을 하나로 막는다 — 같은 클레임의 재요청은 이 행을 찾아 멱등으로 답한다.
 * {@code idx_stock_restoration_reservation_id} 는 선점 하나의 "이미 복원한 수량" 합을 읽을 때 탄다.
 *
 * <p>{@code @Version} 이 없고 아이디(Snowflake)를 직접 넣으므로 {@link Persistable} 로 새 행이면 {@code persist}(INSERT) 하게 한다
 * ({@code coding-conventions.md} §9-1). merge 로 가면 같은 클레임의 동시 생성이 유니크 키 대신 SELECT 뒤 INSERT 경합이 된다.
 */
@Entity
@Table(
    name = "stock_restoration",
    comment = "결제 후 재고 복원 (클레임 하나에 한 행, append-only)",
    uniqueConstraints = @UniqueConstraint(name = "uk_stock_restoration_claim_id", columnNames = "claim_id"),
    indexes = @Index(name = "idx_stock_restoration_reservation_id", columnList = "reservation_id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockRestorationEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "재고 복원 아이디 (Snowflake)")
    private Long id;

    @Column(name = "claim_id", nullable = false, comment = "클레임 아이디 (외부 FK: order-service 클레임 id). 클레임당 복원 1건 — 멱등 키")
    private Long claimId;

    @Column(name = "reservation_id", nullable = false, comment = "복원 대상 확정 선점 아이디 (FK: stock_reservation.id)")
    private Long reservationId;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (외부 FK: order-service 주문 id). 선점에서 복사한 운영 조회용 값")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20, comment = "복원 사유 (CANCELLATION / RETURN)")
    private StockRestorationReason reason;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
