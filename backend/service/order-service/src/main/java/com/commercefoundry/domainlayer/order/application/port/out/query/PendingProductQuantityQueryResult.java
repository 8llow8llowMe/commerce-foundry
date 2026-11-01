package com.commercefoundry.domainlayer.order.application.port.out.query;

/**
 * 회원의 결제 대기 주문에 담긴 한 상품의 수량 합 ({@code OrderRepository} JPQL 생성자 투영, #426). 결제 대기 주문은 재고 선점을 쥐고 있다.
 */
public record PendingProductQuantityQueryResult(long productId, long quantity) {
}
