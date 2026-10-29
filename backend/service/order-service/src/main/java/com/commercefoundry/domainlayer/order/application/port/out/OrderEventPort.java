package com.commercefoundry.domainlayer.order.application.port.out;

/**
 * 주문 도메인 이벤트 발행 — transactional outbox 에 넣는다 ({@code order-flow-guide.md} §3-5). 호출자 트랜잭션 안에서 불러야 상태 전이와 함께 커밋 · 롤백된다.
 */
public interface OrderEventPort {

    /** 주문이 결제 기한을 넘겨 만료됐다 — product-service 가 받아 {@code reservationId} 선점을 해제한다. */
    void expired(long orderId, long reservationId);
}
