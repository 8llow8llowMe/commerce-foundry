package com.commercefoundry.domainlayer.order.application.port.out.query;

/** 한 주문의 접수 중 반품 항목별 수량. */
public record RequestedReturnQuantityQueryResult(long orderItemId, long quantity) {
}
