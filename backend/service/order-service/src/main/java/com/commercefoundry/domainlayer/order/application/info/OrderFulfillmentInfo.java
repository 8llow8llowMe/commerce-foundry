package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.util.Objects;

/**
 * 출고 · 배송 완료 반영 결과 (#18) — delivery-service 가 송장에 스냅숏할 값이다.
 *
 * @param status 반영 뒤 지금 상태. 이미 출고 · 배송 완료된 뒤였으면 그 상태 그대로다(구매 확정 · 반품 등)
 * @param countryCode 배송지 국가 (주문 배송지 스냅숏, ISO 3166-1 alpha-2) — delivery 가 국내 · 해외 송장을 가른다
 * @param changed 이번 요청으로 상태를 바꿨는가. 멱등 재요청이면 false
 */
public record OrderFulfillmentInfo(long orderId, long memberId, OrderStatus status, String countryCode, boolean changed) {

    public OrderFulfillmentInfo {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(countryCode, "countryCode");
    }

    public static OrderFulfillmentInfo of(Order order, boolean changed) {
        return new OrderFulfillmentInfo(order.id(), order.memberId(), order.status(), order.shippingAddress().countryCode(), changed);
    }
}
