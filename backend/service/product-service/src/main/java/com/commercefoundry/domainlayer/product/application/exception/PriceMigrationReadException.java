package com.commercefoundry.domainlayer.product.application.exception;

/** 원천·쿼리·바인딩 값을 cause나 메시지로 전파하지 않는 내부 실패다. HTTP 오류가 아니다. */
public final class PriceMigrationReadException extends RuntimeException {
    public enum Reason { INVALID_CRITERIA, READ_FAILED }
    private final Reason reason;

    public PriceMigrationReadException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
