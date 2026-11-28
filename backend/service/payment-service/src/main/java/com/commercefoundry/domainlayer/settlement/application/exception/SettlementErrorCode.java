package com.commercefoundry.domainlayer.settlement.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 정산 수집 · 대사 오류 코드 (#219). payment-service 의 {@code PAYMENT_1xx} 와 섞지 않으려고 컨텍스트 접두어를 따로 연다 ({@code coding-conventions.md} §8-2).
 * 폴백(깨진 JSON · 형식 오류)은 서비스 공용 {@code PAYMENT_100} 이다.
 */
@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode {

    SETTLEMENT_DISABLED("SETTLEMENT_001", "정산 대사가 꺼져 있습니다.", HttpStatus.CONFLICT),
    RECONCILE_CONFLICT("SETTLEMENT_002", "같은 매출일의 정산 수집 · 대사가 겹쳤습니다. 잠시 뒤 다시 시도해 주세요.", HttpStatus.CONFLICT),
    PG_COLLECTION_FAILED("SETTLEMENT_003", "PG 정산 내역을 수집하지 못했습니다. 잠시 뒤 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    SOLD_DATE_OUT_OF_RANGE("SETTLEMENT_102", "매출일은 오늘(KST)부터 90일 전 사이여야 합니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
