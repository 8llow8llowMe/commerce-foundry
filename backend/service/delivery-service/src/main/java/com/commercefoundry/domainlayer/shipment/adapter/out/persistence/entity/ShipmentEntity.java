package com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.ShippingScope;
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
 * 송장(배송) — 주문당 1건이다({@code uk_shipment_order_id}, {@code order-flow-guide.md} §4). 상태 엔티티라 {@code @Version} 으로 저장한다 —
 * 새 행은 버전이 null 이라 persist(INSERT), 읽어 온 행은 merge(버전 비교 UPDATE)다. 변경 메서드를 두지 않는다 — 도메인 {@code Shipment} 가 바꾸고 매퍼가 옮긴다.
 *
 * <p>인덱스 — 어드민 목록(PR-B2)이 상태별 · 주문 반영 여부별 최신순({@code where status = ? order by id desc}, {@code ?synced=false})으로 읽는다.
 *
 * <p>check 제약 — 주문에 반영된 송장은 회원 스냅숏이 있고, 배송 완료 시각은 배송 완료 상태에만 있다. 도메인 생성자와 같은 규칙을 DB 에서도 막는다.
 */
@Entity
@Table(
    name = "shipment",
    comment = "송장(배송) — 주문당 1건",
    uniqueConstraints = @UniqueConstraint(name = "uk_shipment_order_id", columnNames = "order_id"),
    indexes = {
        @Index(name = "idx_shipment_status_id", columnList = "status, id"),
        @Index(name = "idx_shipment_order_synced_id", columnList = "order_synced, id")
    },
    check = {
        @CheckConstraint(name = "ck_shipment_synced_member", constraint = "order_synced = false or member_id is not null"),
        @CheckConstraint(
            name = "ck_shipment_delivered_at_status",
            constraint = "(status = 'DELIVERED' and delivered_at is not null) or (status <> 'DELIVERED' and delivered_at is null)"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShipmentEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "송장(배송) 아이디 (Snowflake) — order 에 deliveryId 로 보낸다")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (외부 FK: order-service orders.id). 주문당 1건")
    private Long orderId;

    @Column(name = "member_id", comment = "주문한 회원 아이디 (외부 FK: auth-service member.id) — 출고 반영 응답의 스냅숏. 반영 전에는 null")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", nullable = false, length = 20, comment = "택배사 코드 (CarrierCode)")
    private CarrierCode carrier;

    @Column(name = "tracking_number", nullable = false, length = 50, comment = "송장 번호 (영문 · 숫자 · 하이픈)")
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "배송 상태 (SHIPPED · IN_TRANSIT · DELIVERED)")
    private ShipmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_scope", length = 20, comment = "배송 범위 (DOMESTIC · INTERNATIONAL) — 출고 반영 응답의 배송 국가로 정한다. 반영 전이거나 국가를 읽지 못했으면 null")
    private ShippingScope shippingScope;

    @Column(name = "order_synced", nullable = false, comment = "주문 상태 반영 여부. 반영된 송장은 지우거나 다른 송장으로 바꾸지 않는다")
    private boolean orderSynced;

    @Column(name = "shipped_at", nullable = false, comment = "출고 시각 — order 의 shipped_at 과 같은 값")
    private Instant shippedAt;

    @Column(name = "delivered_at", comment = "배송 완료 시각. 배송 완료 상태에만 있다")
    private Instant deliveredAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
