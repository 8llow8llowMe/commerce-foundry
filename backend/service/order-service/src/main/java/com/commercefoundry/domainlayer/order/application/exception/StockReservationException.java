package com.commercefoundry.domainlayer.order.application.exception;

import java.util.Objects;
import lombok.Getter;

/** Port failure reasons; remote messages and codes never become customer-facing messages. */
@Getter
public class StockReservationException extends RuntimeException {

    private final Failure failure;

    public StockReservationException(Failure failure, Throwable cause) {
        super("stock reservation failed: " + failure, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public StockReservationException(Failure failure) {
        this(failure, null);
    }

    public enum Failure {
        INSUFFICIENT, NOT_PURCHASABLE, ITEMS_MISMATCH, CONFLICT,
        /** 확정 대상 선점이 이미 해제 · 만료됐다(확정의 409 {@code PRODUCT_025}, #308) — 같은 주문 아이디로 재선점할 신호다. 선점 호출에서는 나오지 않는다. */
        RELEASED,
        /** 확정 대상 선점이 없다(확정의 404 {@code PRODUCT_003}, #308) — 재선점할 신호다. 선점 호출에서는 나오지 않는다. */
        NOT_FOUND,
        /** Transport, timeout, 5xx, circuit or invalid success response: reservation outcome is unknown. */
        UNKNOWN,
        /** Unexpected 4xx: request, authentication or contract rejection. */
        REJECTED
    }
}
