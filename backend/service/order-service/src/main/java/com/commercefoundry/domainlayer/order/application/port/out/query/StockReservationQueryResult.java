package com.commercefoundry.domainlayer.order.application.port.out.query;

import java.time.Instant;
import java.util.Objects;

/**
 * product-service 재고 선점 결과 ({@code POST /internal/v1/stock-reservations}). 원격 응답 모양(Feign DTO)은 adapter 밖으로 나오지 않고 이 값으로만 나온다.
 * 항목 · 차감 여부는 주문이 쓰지 않아 옮기지 않는다.
 *
 * @param expiresAt 선점 만료 시각 (UTC)
 */
public record StockReservationQueryResult(long reservationId, long orderId, Instant expiresAt) {

    public StockReservationQueryResult {
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
