package com.commercefoundry.domainlayer.order.application.port.out.query;

/**
 * 한 상품의 수량 합 (JPQL 생성자 투영). 회원의 결제 대기 주문에 담긴 수량({@code OrderRepository}, #426)과 진행 중인 주문 요청 기록에 담긴 수량
 * ({@code OrderRequestItemRepository}, #436)에 함께 쓴다 — 둘 다 재고 선점을 쥐었거나 쥐려는 수량이다.
 */
public record PendingProductQuantityQueryResult(long productId, long quantity) {
}
