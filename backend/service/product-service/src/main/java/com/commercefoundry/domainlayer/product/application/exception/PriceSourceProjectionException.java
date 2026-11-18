package com.commercefoundry.domainlayer.product.application.exception;

import java.util.Objects;

/** 가격 투영 실패의 고정 사유만 노출한다. */
public final class PriceSourceProjectionException extends RuntimeException {
    private final Reason reason;

    public PriceSourceProjectionException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name(), null, false, true);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_SNAPSHOT,
        INVALID_PRICE_BOOK,
        PRICE_DRIFT
    }
}
