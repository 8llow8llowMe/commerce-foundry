package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
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
 * 재고 선점 헤더 — 주문 하나에 한 행. 항목은 {@link StockReservationItemEntity} 다.
 *
 * <p>상태는 도메인 전이 → 이 엔티티로 매핑 → {@code save}(merge) 로 바꾼다 — JPQL 로 상태를 바꾸지 않는다. {@code @Version} 이 확정 · 해제 · 만료가
 * 동시에 올 때 하나만 통과시킨다. 버전이 {@code Long}(wrapper)이라 새 행은 버전이 null 이고, Spring Data 는 그것으로 새 행을 판정해 {@code persist} 한다
 * — 아이디를 직접 넣어도 {@code Persistable} 이 필요 없다.
 *
 * <p>{@code active_order_id} 는 RESERVED · CONFIRMED 동안만 주문 아이디이고 RELEASED 면 null 이다. 유니크 키가 주문당 활성 선점을 하나로 막고,
 * 해제된 주문은 다시 선점할 수 있다(PostgreSQL 유니크 키는 null 을 겹치게 둔다).
 * 만료 스윕은 {@code (status, expires_at, id)} 인덱스로 RESERVED 중 만료 시각이 지난 것을 오래된 순으로 읽고, 배치마다 {@code (expires_at, id)} keyset 으로 이어 읽는다.
 */
@Entity
@Table(
    name = "stock_reservation",
    comment = "재고 선점 (주문 하나에 한 행)",
    uniqueConstraints = @UniqueConstraint(name = "uk_stock_reservation_active_order_id", columnNames = "active_order_id"),
    indexes = {
        @Index(name = "idx_stock_reservation_status_expires_at_id", columnList = "status, expires_at, id"),
        @Index(name = "idx_stock_reservation_order_id", columnList = "order_id")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockReservationEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "재고 선점 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (외부 FK: order-service 주문 id)")
    private Long orderId;

    @Column(name = "active_order_id", comment = "활성 선점의 주문 아이디 — RESERVED · CONFIRMED 동안만 order_id 와 같고 RELEASED 면 null (주문당 활성 선점 1건)")
    private Long activeOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "선점 상태 (RESERVED / CONFIRMED / RELEASED)")
    private StockReservationStatus status;

    @Column(name = "expires_at", nullable = false, comment = "만료 시각 — RESERVED 로 이 시각이 지나면 스케줄러가 해제한다")
    private Instant expiresAt;

    @Column(name = "confirmed_at", comment = "확정 시각 (CONFIRMED 일 때만)")
    private Instant confirmedAt;

    @Column(name = "released_at", comment = "해제 시각 (RELEASED 일 때만)")
    private Instant releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 20, comment = "해제 사유 (EXPIRED / RELEASED_BY_CALLER). RELEASED 일 때만")
    private StockReleaseReason releaseReason;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
