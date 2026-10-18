package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 재고 선점 해제 사유. 해제된 선점({@link StockReservationStatus#RELEASED})에만 있다.
 */
public enum StockReleaseReason {

    /** 결제 없이 만료 시각이 지나 스케줄러가 풀었다 */
    EXPIRED,
    /** 호출한 서비스(order-service)가 결제 전 취소 · 주문 생성 실패로 풀었다 */
    RELEASED_BY_CALLER
}
