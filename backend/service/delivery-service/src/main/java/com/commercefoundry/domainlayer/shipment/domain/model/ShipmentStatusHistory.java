package com.commercefoundry.domainlayer.shipment.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 배송 상태 이력 한 줄 — append-only 다. 등록 줄은 {@code fromStatus} 가 null 이다.
 *
 * <p>예외 하나 — 주문에 반영되지 못하고 지워지는 대기 송장(404 · 409 {@code ORDER_020} · 400 시각 범위)은 이력도 함께 지운다. 그 송장은 출고된 적이 없다.
 * 배송 완료를 order 가 거절해 되돌릴 때(#18 PR-B2)는 지우지 않고 되돌림 줄({@link #completionReverted}, 자동 처리)을 더한다 — 운영자가 누른 배송 완료는 일어난 일이다.
 *
 * @param changedById 바꾼 관리자 memberId. {@link ShipmentChangedBy#ADMIN} 이면 있다
 * @param reason 사유 ({@value #REASON_MAX_LENGTH}자 이하) — 어드민 배송 상태 변경이 받은 값. 없으면 null
 * @param changedAt 바뀐 시각. 아직 저장하지 않은 이력이면 null — 저장 때 감사 리스너가 채운다
 */
public record ShipmentStatusHistory(
    long id, long shipmentId, ShipmentStatus fromStatus, ShipmentStatus toStatus, ShipmentChangedBy changedBy, Long changedById, String reason,
    Instant changedAt
) {

    /** 사유 최대 길이 — 어드민 주문 상태 변경 사유와 같다. */
    public static final int REASON_MAX_LENGTH = 200;

    public ShipmentStatusHistory {
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedBy, "changedBy");
        if (id <= 0 || shipmentId <= 0) {
            throw new IllegalArgumentException("이력 · 송장 아이디는 양수여야 합니다: id=%d, shipmentId=%d".formatted(id, shipmentId));
        }
        if (changedBy == ShipmentChangedBy.ADMIN && (changedById == null || changedById <= 0)) {
            throw new IllegalArgumentException("관리자 변경 이력은 관리자 아이디가 있어야 합니다: id=" + id);
        }
        if (reason != null && reason.length() > REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("사유는 %d자 이하입니다: 길이 %d".formatted(REASON_MAX_LENGTH, reason.length()));
        }
    }

    /** 송장 등록 줄 — 없음 → 출고 완료, 등록한 관리자. */
    public static ShipmentStatusHistory registered(long id, long shipmentId, long adminMemberId) {
        return new ShipmentStatusHistory(id, shipmentId, null, ShipmentStatus.SHIPPED, ShipmentChangedBy.ADMIN, adminMemberId, null, null);
    }

    /** 어드민 배송 상태 변경 줄 (#18 PR-B2). */
    public static ShipmentStatusHistory changedByAdmin(
        long id, long shipmentId, ShipmentStatus fromStatus, ShipmentStatus toStatus, long adminMemberId, String reason
    ) {
        return new ShipmentStatusHistory(
            id, shipmentId, Objects.requireNonNull(fromStatus, "fromStatus"), toStatus, ShipmentChangedBy.ADMIN, adminMemberId, reason, null);
    }

    /** order 가 배송 완료를 거절해 되돌린 줄 — 배송 완료 → 직전 상태, 자동 처리 (#18 PR-B2). */
    public static ShipmentStatusHistory completionReverted(long id, long shipmentId, ShipmentStatus restoredStatus) {
        return new ShipmentStatusHistory(id, shipmentId, ShipmentStatus.DELIVERED, restoredStatus, ShipmentChangedBy.SYSTEM, null, null, null);
    }
}
