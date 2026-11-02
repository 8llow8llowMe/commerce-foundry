package com.commercefoundry.domainlayer.shipment.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.shipment.adapter.in.web.dto.response.ShipmentResponse;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import org.springframework.stereotype.Component;

/**
 * 아이디는 문자열이다.
 *
 * <p>배송 완료가 주문에 아직 반영되지 않았으면({@code completionPending}) 고객에게는 배송 중 · 배송 완료 시각 없음으로 보인다 — 주문이 아직 배송 중이라
 * 반품 · 구매 확정을 할 수 없는데 배송 완료로 보이면 고객 화면이 주문 상세와 어긋난다. order 가 거절해 되돌려질 수도 있다. 어드민 응답은 송장 그대로다
 * ({@code order-flow-guide.md} §4 "고객 노출").
 */
@Component
public class ShipmentWebPresenter {

    public ShipmentResponse toResponse(Shipment shipment) {
        boolean completionPending = shipment.completionPending();
        return ShipmentResponse.builder()
            .deliveryId(String.valueOf(shipment.id()))
            .orderId(String.valueOf(shipment.orderId()))
            .carrier(shipment.carrier().toMetadata())
            .trackingNumber(shipment.trackingNumber())
            .status((completionPending ? ShipmentStatus.IN_TRANSIT : shipment.status()).toMetadata())
            .shippedAt(shipment.shippedAt())
            .deliveredAt(completionPending ? null : shipment.deliveredAt())
            .build();
    }
}
