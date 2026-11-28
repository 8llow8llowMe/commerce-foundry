package com.commercefoundry.domainlayer.product.application.exception;

import java.util.Objects;

/** HTTP 계약이 없는 내부 읽기 실패다. 원천 값·원인·suppressed 예외를 보관하지 않는다. */
public final class PriceSourceSnapshotReadException extends RuntimeException {
    private final Reason reason;

    public PriceSourceSnapshotReadException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name(), null, false, true);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_INPUT,
        PRODUCT_MISSING,
        ROW_LIMIT_EXCEEDED,
        READ_FAILED,
        /** 호출자 TX 참여 읽기에서 호출자 TX가 없거나 read-only·REPEATABLE_READ 이상이 아니다. */
        CALLER_TRANSACTION_REQUIRED
    }
}
