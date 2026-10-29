package com.commercefoundry.domainlayer.order.application.port.out.query;

import java.time.Instant;
import java.util.Objects;

/**
 * product-service 재고 선점 결과 ({@code POST /internal/v1/stock-reservations}). 원격 응답 모양(Feign DTO)은 adapter 밖으로 나오지 않고 이 값으로만 나온다.
 * 어댑터가 RESERVED 상태와 항목 전체를 요청에 대조한 뒤 항목을 버린다. 차감 여부(deducted)는 주문이 판단하지 않아 옮기지 않는다.
 *
 * @param expiresAt 선점 만료 시각 (UTC)
 */
public record StockReservationQueryResult(long reservationId, long orderId, Instant expiresAt) {

    public StockReservationQueryResult {
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (reservationId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("positive reservation and order IDs required");
        }
    }
}
