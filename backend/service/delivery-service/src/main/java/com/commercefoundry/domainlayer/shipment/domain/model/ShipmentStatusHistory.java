package com.commercefoundry.domainlayer.shipment.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 배송 상태 이력 한 줄 — append-only 다. 등록 줄은 {@code fromStatus} 가 null 이다.
 *
 * <p>예외 하나 — 주문에 반영되지 못하고 지워지는 대기 송장(404 · 409 {@code ORDER_020} · 400 시각 범위)은 이력도 함께 지운다. 그 송장은 출고된 적이 없다.
 *
 * @param changedById 바꾼 관리자 memberId. {@link ShipmentChangedBy#ADMIN} 이면 있다
 * @param changedAt 바뀐 시각. 아직 저장하지 않은 이력이면 null — 저장 때 감사 리스너가 채운다
 */
public record ShipmentStatusHistory(
    long id, long shipmentId, ShipmentStatus fromStatus, ShipmentStatus toStatus, ShipmentChangedBy changedBy, Long changedById, Instant changedAt
) {

    public ShipmentStatusHistory {
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedBy, "changedBy");
        if (id <= 0 || shipmentId <= 0) {
            throw new IllegalArgumentException("이력 · 송장 아이디는 양수여야 합니다: id=%d, shipmentId=%d".formatted(id, shipmentId));
        }
        if (changedBy == ShipmentChangedBy.ADMIN && (changedById == null || changedById <= 0)) {
            throw new IllegalArgumentException("관리자 변경 이력은 관리자 아이디가 있어야 합니다: id=" + id);
        }
    }

    /** 송장 등록 줄 — 없음 → 출고 완료, 등록한 관리자. */
    public static ShipmentStatusHistory registered(long id, long shipmentId, long adminMemberId) {
        return new ShipmentStatusHistory(id, shipmentId, null, ShipmentStatus.SHIPPED, ShipmentChangedBy.ADMIN, adminMemberId, null);
    }
}
