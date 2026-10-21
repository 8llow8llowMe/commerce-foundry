package com.commercefoundry.domainlayer.order.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 이 주문 아이디로 잡은 재고 선점 — {@code OrderStockProcessor} 가 조회 결과를 확인(주문 아이디 · 만료 시각)한 뒤 만든다.
 *
 * @param expiresAt 선점 만료 시각 — 주문의 결제 기한이 된다. 언제나 지금보다 뒤다
 */
public record StockReservation(long reservationId, long orderId, Instant expiresAt) {

    public StockReservation {
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
