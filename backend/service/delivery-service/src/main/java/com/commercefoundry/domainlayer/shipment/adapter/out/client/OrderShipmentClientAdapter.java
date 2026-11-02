package com.commercefoundry.domainlayer.shipment.adapter.out.client;

import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.OrderShipmentClient;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto.OrderShipmentInternalRequest;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.support.OrderShipmentInternalResponseSupport;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
}
