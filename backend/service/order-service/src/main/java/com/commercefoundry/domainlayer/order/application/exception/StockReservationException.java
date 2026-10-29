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
        /** Transport, timeout, 5xx, circuit or invalid success response: reservation outcome is unknown. */
        UNKNOWN,
        /** Unexpected 4xx: request, authentication or contract rejection. */
        REJECTED
    }
}
