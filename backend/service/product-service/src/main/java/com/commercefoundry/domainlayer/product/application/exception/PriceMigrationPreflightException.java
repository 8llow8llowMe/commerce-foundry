package com.commercefoundry.domainlayer.product.application.exception;

/** 사전검증 실행 중단 사유. 원천 값·cause를 싣지 않는 내부 실패이며 부분 결과를 반환하지 않는다. HTTP 오류가 아니다. */
public final class PriceMigrationPreflightException extends RuntimeException {
    /**
     * SCAN_LIMIT_EXCEEDED: 네 lane 합계 스캔 상한 초과.
     * PORT_CONTRACT_VIOLATION: 읽기 Port가 상한 null 또는 keyset 계약(증가 ID·상한·lane·크기)을 어긴 페이지를 반환.
     */
    public enum Reason { SCAN_LIMIT_EXCEEDED, PORT_CONTRACT_VIOLATION }
    private final Reason reason;

    public PriceMigrationPreflightException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
