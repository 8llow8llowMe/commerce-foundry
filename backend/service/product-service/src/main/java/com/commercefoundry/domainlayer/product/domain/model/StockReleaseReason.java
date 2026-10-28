package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 재고 선점 해제 사유. 해제된 선점({@link StockReservationStatus#RELEASED})에만 있다.
 */
public enum StockReleaseReason {

    /** 결제 없이 만료 시각이 지나 스케줄러가 풀었다 (안전망 스윕) */
    EXPIRED,
    /** 호출한 서비스(order-service)가 internal API 로 풀었다 — 주문 생성 실패 보상 (이벤트 발행 전에는 결제 전 취소도) */
    RELEASED_BY_CALLER,
    /** order-service 의 주문 만료 이벤트({@code order-expired})로 풀었다 */
    ORDER_EXPIRED,
    /** order-service 의 결제 전 주문 취소 이벤트({@code order-canceled})로 풀었다 */
    ORDER_CANCELED
}
