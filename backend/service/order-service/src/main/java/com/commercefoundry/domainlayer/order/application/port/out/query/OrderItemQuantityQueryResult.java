package com.commercefoundry.domainlayer.order.application.port.out.query;

/** 한 주문의 주문 항목별 클레임 수량 합 — 반품(#17 B1) · 취소(#684)가 같은 질의 한 줄을 쓴다. 상태 집합은 도메인이 준다. */
public record OrderItemQuantityQueryResult(long orderItemId, long quantity) {
}
