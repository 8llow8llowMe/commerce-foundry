package com.commercefoundry.domainlayer.shipment.application.port.in;

import com.commercefoundry.domainlayer.shipment.adapter.in.web.dto.response.ShipmentResponse;

public interface ShipmentWebUseCase {

    /**
     * 고객 주문의 배송 정보 (#18 PR-B2). 송장이 없거나 남의 주문이거나 출고가 주문에 반영되기 전이면 null 이다 — 주문 존재 · 소유 404 는 주문 상세의 몫이다
     * ({@code api-design-guide.md} §2-2).
     *
     * @param memberId access 토큰의 회원
     */
    ShipmentResponse getOrderShipment(long orderId, long memberId);
}
