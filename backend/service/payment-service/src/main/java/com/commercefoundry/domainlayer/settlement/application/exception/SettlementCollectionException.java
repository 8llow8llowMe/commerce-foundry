package com.commercefoundry.domainlayer.settlement.application.exception;

import lombok.Getter;

/**
 * PG 정산 수집 경계의 실패 분류 — 정산 게이트웨이 포트가 던진다. PG 응답 본문 · 원인 예외 · 결제키는 싣지 않는다(로그 · 스택으로 퍼지지 않게).
 * Facade 가 받아 매출일을 FAILED 로 기록한 뒤 {@code SETTLEMENT_003} 으로 바꾼다.
 */
@Getter
public final class SettlementCollectionException extends RuntimeException {

    private final Failure failure;

    public SettlementCollectionException(Failure failure) {
        super("PG settlement collection failed: " + failure.name());
        this.failure = failure;
    }

    public enum Failure {
        /** 5xx · 타임아웃 · 연결 실패 — 재시도하지 않고 끝낸다. 호출자(정기 작업)가 다시 부른다. */
        UNAVAILABLE,
        /** 429 가 제한 재시도 뒤에도 계속됐다. */
        RATE_LIMITED,
        /** 429 가 아닌 4xx — 키 · 요청 문제다. 다시 불러도 풀리지 않을 수 있다. */
        REJECTED,
        /** 응답 모양 · 값이 가정한 계약과 다르다. */
        CONTRACT_VIOLATION
    }
}
