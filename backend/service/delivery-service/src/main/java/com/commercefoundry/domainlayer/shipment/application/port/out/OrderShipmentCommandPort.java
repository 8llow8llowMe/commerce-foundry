package com.commercefoundry.domainlayer.shipment.application.port.out;

import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import java.time.Instant;

/**
 * order-service 에 출고를 반영한다 ({@code POST /internal/v1/orders/{orderId}/shipment}, {@code api-design-guide.md} §2-18). order 가 멱등이라
 * 같은 주문의 재요청은 이미 배송 중 뒤면 바꾸지 않고 200 이다.
 */
public interface OrderShipmentCommandPort {

    /**
     * @param shipmentId order 가 추적 로그에 남기는 송장 아이디
     * @param shippedAt 출고 시각 — order 의 {@code shipped_at} 이 된다
     * @throws OrderShipmentException 200 이 아니다 — {@link OrderShipmentException.Failure} 가 대기 송장 행을 지울지 남길지 정한다
     */
    OrderShipmentConfirmation ship(long orderId, long shipmentId, Instant shippedAt);
}
