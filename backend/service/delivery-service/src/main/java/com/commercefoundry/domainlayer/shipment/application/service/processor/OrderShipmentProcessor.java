package com.commercefoundry.domainlayer.shipment.application.service.processor;

import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 송장을 order-service 주문 상태(배송 중)로 반영한다. 트랜잭션을 걸지 않는다 — 원격 호출 동안 커넥션을 잡지 않게, 저장 단계({@link ShipmentRegisterProcessor})와 나눈다.
 */
@Component
@RequiredArgsConstructor
public class OrderShipmentProcessor {

    private final OrderShipmentCommandPort orderShipmentCommandPort;

    /**
     * 송장 아이디 · 출고 시각을 그대로 보낸다 — 재시도도 같은 송장 아이디다.
     *
     * @throws OrderShipmentException 200 이 아니다
     */
    public OrderShipmentConfirmation ship(Shipment shipment) {
        return orderShipmentCommandPort.ship(shipment.orderId(), shipment.id(), shipment.shippedAt());
    }
}
