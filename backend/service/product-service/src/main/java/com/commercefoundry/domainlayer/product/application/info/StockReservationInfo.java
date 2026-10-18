package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import java.time.Instant;
import java.util.List;

/**
 * 재고 선점 결과 — 생성 · 확정 · 해제 모두 같은 모양이다.
 *
 * @param items SKU 아이디 오름차순
 */
public record StockReservationInfo(long reservationId, long orderId, StockReservationStatus status, Instant expiresAt, List<Item> items) {

    public static StockReservationInfo from(StockReservation reservation) {
        return new StockReservationInfo(
            reservation.id(), reservation.orderId(), reservation.status(), reservation.expiresAt(),
            reservation.items().stream().map(item -> new Item(item.skuId(), item.quantity(), item.deducted())).toList()
        );
    }

    /** @param deducted 선점 때 재고를 차감했는가. 무제한 SKU 는 false */
    public record Item(long skuId, int quantity, boolean deducted) {
    }
}
