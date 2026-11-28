package com.commercefoundry.domainlayer.order.application.port.out.query;

/** 여러 주문의 주문 항목별 클레임 수량 합 (#684) — 주문 아이디 IN 묶음 질의 한 줄이다. */
public record OrderItemClaimQuantityQueryResult(long orderId, long orderItemId, long quantity) {
}
