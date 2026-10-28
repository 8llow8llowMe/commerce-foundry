package com.commercefoundry.domainlayer.product.adapter.in.event.dto;

/**
 * 주문 만료 · 결제 전 취소 이벤트의 페이로드 중 product-service 가 읽는 부분 — 토픽 {@code <ns>.order.order-expired} · {@code <ns>.order.order-canceled},
 * 키는 주문 아이디. 계약 정본은 {@code order-flow-guide.md} §3-5 의 JSON 이다(발행은 order-service). 모르는 필드는 무시한다.
 *
 * <p>SKU · 수량은 싣지 않는다 — product-service 가 선점 항목({@code stock_reservation_item})을 갖고 있다. 발생 시각은 봉투의 {@code occurredAt} 이다.
 *
 * @param orderId 주문 아이디 (Snowflake, 문자열 — JS 정밀도). 선점의 주문 아이디와 교차 검증한다
 * @param reservationId 해제할 재고 선점 아이디 (문자열) — 해제 키다
 * @param reason order-service 가 정한 사유 코드 (예: {@code PAYMENT_TIMEOUT}). product-service 는 분기에 쓰지 않는다
 */
public record OrderStockReleaseMessage(String orderId, String reservationId, String reason) {

    /** 주문 만료 — 결제 없이 결제 기한이 지났다. 해제 사유 {@code ORDER_EXPIRED}. */
    public static final String EVENT_TYPE_EXPIRED = "order-expired";
    /** 결제 전 주문 취소. 해제 사유 {@code ORDER_CANCELED}. */
    public static final String EVENT_TYPE_CANCELED = "order-canceled";
    /** 이 이벤트들을 내는 서비스 — 토픽 이름의 생산 서비스 토막. */
    public static final String PRODUCER_SERVICE = "order";
    public static final int SCHEMA_VERSION = 1;
}
