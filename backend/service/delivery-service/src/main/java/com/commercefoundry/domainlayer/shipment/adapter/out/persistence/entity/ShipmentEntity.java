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
import org.hibernate.annotations.ColumnDefault;

/**
 * 송장(배송) — 주문당 1건이다({@code uk_shipment_order_id}, {@code order-flow-guide.md} §4). 상태 엔티티라 {@code @Version} 으로 저장한다 —
 * 새 행은 버전이 null 이라 persist(INSERT), 읽어 온 행은 merge(버전 비교 UPDATE)다. 변경 메서드를 두지 않는다 — 도메인 {@code Shipment} 가 바꾸고 매퍼가 옮긴다.
 *
 * <p>인덱스 — 어드민 목록(#18 PR-B2)이 상태별 · 주문 반영 여부별 최신순({@code where status = ? order by id desc})으로 읽는다. {@code ?synced=false} 는
 * "지금 상태가 order 에 반영되지 않은 송장" = {@code order_synced = false or completion_pending = true} 라 두 boolean 인덱스를 BitmapOr 로 합친다 —
 * 두 값 모두 드물어(반영 대기는 장애 · 경합 때만 남는다) 상태와 묶은 복합 인덱스를 따로 두지 않는다.
 *
 * <p>check 제약 — 주문에 반영된 송장은 회원 스냅숏이 있고, 반영 전 송장은 출고 완료 상태이며, 배송 완료 시각은 배송 완료 상태에만 있고, 배송 완료 반영 대기는
 * 배송 완료 상태에만 있고, 배송 완료 직전 상태는 반영 대기에만(출고 완료 · 배송 중으로) 있다. 도메인 생성자와 같은 규칙을 DB 에서도 막는다.
 *
 * <p>{@code completion_pending} 은 DDL 기본값 {@code false} 를 둔다({@code @ColumnDefault}) — dev 는 {@code ddl-auto: update} 라 행이 있는 기존 테이블에
 * {@code add column ... not null} 을 보내는데, 기본값이 없으면 기존 행이 null 이라 PostgreSQL 이 거절해 기동이 실패한다. 기본값이 있으면 기존 행은 "대기 아님" 으로 채워진다.
 * 애플리케이션은 언제나 값을 넣으므로 기본값은 컬럼 추가 때만 쓰인다.
 */
@Entity
@Table(
    name = "shipment",
    comment = "송장(배송) — 주문당 1건",
    uniqueConstraints = @UniqueConstraint(name = "uk_shipment_order_id", columnNames = "order_id"),
    indexes = {
        @Index(name = "idx_shipment_status_id", columnList = "status, id"),
        @Index(name = "idx_shipment_order_synced_id", columnList = "order_synced, id"),
        @Index(name = "idx_shipment_completion_pending_id", columnList = "completion_pending, id")
    },
    check = {
        @CheckConstraint(name = "ck_shipment_synced_member", constraint = "order_synced = false or member_id is not null"),
        @CheckConstraint(name = "ck_shipment_unsynced_shipped", constraint = "order_synced = true or status = 'SHIPPED'"),
        @CheckConstraint(
            name = "ck_shipment_delivered_at_status",
            constraint = "(status = 'DELIVERED' and delivered_at is not null) or (status <> 'DELIVERED' and delivered_at is null)"
        ),
        @CheckConstraint(name = "ck_shipment_completion_pending_status", constraint = "completion_pending = false or status = 'DELIVERED'"),
        @CheckConstraint(
            name = "ck_shipment_previous_status_pending",
            constraint = "(completion_pending = false and previous_status is null) or (completion_pending = true and previous_status in ('SHIPPED','IN_TRANSIT'))"
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

    @ColumnDefault("false")
    @Column(name = "completion_pending", nullable = false, comment = "배송 완료의 주문 반영 대기 — 배송 완료로 바꿨고 order 가 아직 200 으로 답하지 않았다. 같은 변경 재시도가 수렴한다")
    private boolean completionPending;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20, comment = "배송 완료 직전 상태 (SHIPPED · IN_TRANSIT) — 반영 대기 중에만 있다. order 가 배송 완료를 거절하면 이 상태로 되돌린다")
    private ShipmentStatus previousStatus;

    @Column(name = "shipped_at", nullable = false, comment = "출고 시각 — order 의 shipped_at 과 같은 값")
    private Instant shippedAt;

    @Column(name = "delivered_at", comment = "배송 완료 시각. 배송 완료 상태에만 있다")
    private Instant deliveredAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
