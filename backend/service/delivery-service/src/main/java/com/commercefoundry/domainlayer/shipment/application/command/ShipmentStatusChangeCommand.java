package com.commercefoundry.domainlayer.shipment.application.command;

import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import java.util.Objects;

/**
 * 어드민 배송 상태 변경 (#18 PR-B2).
 *
 * @param status 바꿀 상태 — 배송 중 · 배송 완료만. 출고 완료는 송장 등록이 만든다
 * @param reason 사유 — 배송 상태 이력에 남는다. 앞뒤 공백을 자르고 비면 null 이다(어드민 주문 상태 변경 {@code OrderStatusChangeCommand} 와 같은 규칙)
 * @param adminMemberId 바꾼 관리자 — access 토큰의 memberId (본문으로 받지 않는다). 배송 상태 이력 · 감사 줄에 남는다
 */
public record ShipmentStatusChangeCommand(long shipmentId, ShipmentStatus status, String reason, long adminMemberId) {

    public ShipmentStatusChangeCommand {
        Objects.requireNonNull(status, "status");
        if (status == ShipmentStatus.SHIPPED) {
            throw new IllegalArgumentException("출고 완료로는 바꾸지 않는다 — 송장 등록이 만든다");
        }
        reason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** 사유는 자유 입력이라 로그로 새지 않게 뺀다. */
    @Override
    public String toString() {
        return "ShipmentStatusChangeCommand[shipmentId=" + shipmentId + ", status=" + status + ", reason=" + (reason == null ? "-" : "***")
            + ", adminMemberId=" + adminMemberId + "]";
    }
}
