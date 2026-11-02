package com.commercefoundry.domainlayer.shipment.application.model;

import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;

/**
 * 어드민 송장 목록의 상태 조건 ({@code @RequestParam}, #18 PR-B2). 값은 {@link ShipmentStatus} 와 같다 — 컨트롤러가 domain 타입을 쓰지 않으므로(H1)
 * 입력 타입을 application/model 에 두고 Facade 가 domain 으로 바꾼다 (선례: order {@code OrderStatusFilter}). 둘이 같은지는 {@code ShipmentTest} 가 본다.
 */
public enum ShipmentStatusFilter {

    SHIPPED,
    IN_TRANSIT,
    DELIVERED;

    public ShipmentStatus toShipmentStatus() {
        return ShipmentStatus.valueOf(name());
    }
}
