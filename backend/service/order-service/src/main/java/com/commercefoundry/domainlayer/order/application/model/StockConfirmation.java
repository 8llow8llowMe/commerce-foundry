package com.commercefoundry.domainlayer.order.application.model;

import java.util.Objects;

/**
 * 결제 완료 반영 전 재고 확정의 결과 (#308, {@code OrderStockProcessor#confirmOrReReserve}). 재고가 없어 다시 잡지 못한 것은 예외가 아니라 결과다 —
 * 컨슈머가 {@code STOCK_UNAVAILABLE} 거절 회신으로 끝낸다.
 *
 * @param reservationId 확정 200 · CONFIRMED 를 받은 선점 아이디 — {@code Order#markPaid} 에 넘긴다. {@link Outcome#STOCK_UNAVAILABLE} 이면 0
 */
public record StockConfirmation(Outcome outcome, long reservationId) {

    public StockConfirmation {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.STOCK_UNAVAILABLE ? reservationId != 0 : reservationId <= 0) {
            throw new IllegalArgumentException("confirmed outcomes need a positive reservationId, unavailable needs none");
        }
    }

    public static StockConfirmation confirmed(long reservationId) {
        return new StockConfirmation(Outcome.CONFIRMED, reservationId);
    }

    public static StockConfirmation reReservedAndConfirmed(long reservationId) {
        return new StockConfirmation(Outcome.RE_RESERVED_AND_CONFIRMED, reservationId);
    }

    public static StockConfirmation stockUnavailable() {
        return new StockConfirmation(Outcome.STOCK_UNAVAILABLE, 0);
    }

    /** 확정 200 을 받았는가 — 주문을 결제 완료로 바꿔도 된다. */
    public boolean isConfirmed() {
        return outcome != Outcome.STOCK_UNAVAILABLE;
    }

    public enum Outcome {
        /** 주문의 선점을 그대로 확정했다(이미 확정이었던 것 포함). */
        CONFIRMED,
        /** 주문의 선점이 해제 · 만료(또는 없음)여서 같은 주문 아이디로 다시 선점하고 곧바로 확정했다. */
        RE_RESERVED_AND_CONFIRMED,
        /** 재선점이 재고 부족({@code PRODUCT_021}) · 판매 불가({@code PRODUCT_022})다 — 아무것도 잡지 않았다. */
        STOCK_UNAVAILABLE
    }
}
