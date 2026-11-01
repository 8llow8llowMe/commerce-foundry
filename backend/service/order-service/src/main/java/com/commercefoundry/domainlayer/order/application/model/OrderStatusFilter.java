package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;

/**
 * 어드민 주문 목록의 상태 조건 ({@code @RequestParam}, #17). 값은 {@link OrderStatus} 와 같다 — 컨트롤러가 domain 타입을 쓰지 않으므로(H1)
 * 입력 타입을 application/model 에 두고 Processor 가 domain 으로 바꾼다.
 */
public enum OrderStatusFilter {

    PENDING_PAYMENT,
    EXPIRED,
    PAID,
    MAKING,
    PREPARING,
    SHIPPING,
    DELIVERED,
    PURCHASE_CONFIRMED,
    CANCEL_REQUESTED,
    CANCELED,
    RETURN_REQUESTED,
    RETURNED;

    public OrderStatus toOrderStatus() {
        return OrderStatus.valueOf(name());
    }
}
