package com.commercefoundry.domainlayer.shipment.adapter.out.client;

import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.OrderShipmentClient;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto.OrderDeliveryCompletionInternalRequest;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto.OrderShipmentInternalRequest;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.support.OrderShipmentInternalResponseSupport;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 출고 · 배송 완료는 응답 모양 · 오류 번역이 같다 — 같은 support 로 감싼다 ({@code order-flow-guide.md} §4 실패 표). */
@Component
@RequiredArgsConstructor
public class OrderShipmentClientAdapter implements OrderShipmentCommandPort {

    private final OrderShipmentClient orderShipmentClient;
    private final OrderShipmentInternalResponseSupport orderShipmentInternalResponseSupport;

    @Override
    public OrderShipmentConfirmation ship(long orderId, long shipmentId, Instant shippedAt) {
        OrderShipmentInternalRequest request = new OrderShipmentInternalRequest(String.valueOf(shipmentId), shippedAt);
        return orderShipmentInternalResponseSupport.toConfirmation(
            orderShipmentInternalResponseSupport.call(() -> orderShipmentClient.ship(orderId, request)), orderId);
    }

    @Override
    public OrderShipmentConfirmation completeDelivery(long orderId, long shipmentId, Instant deliveredAt) {
        OrderDeliveryCompletionInternalRequest request = new OrderDeliveryCompletionInternalRequest(String.valueOf(shipmentId), deliveredAt);
        return orderShipmentInternalResponseSupport.toConfirmation(
            orderShipmentInternalResponseSupport.call(() -> orderShipmentClient.completeDelivery(orderId, request)), orderId);
    }
}
