package com.commercefoundry.domainlayer.order.application.port.out.query;

/**
 * product-service 재고 선점 확정 결과 (#308, {@code POST /internal/v1/stock-reservations/{id}/confirmation}). 어댑터가 성공 봉투 · CONFIRMED 상태 ·
 * 요청한 선점 아이디를 확인한 뒤 만든다. 주문 아이디 대조는 부른 Processor 가 한다.
 */
public record StockConfirmationQueryResult(long reservationId, long orderId) {

    public StockConfirmationQueryResult {
        if (reservationId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("positive reservation and order IDs required");
        }
    }
}
