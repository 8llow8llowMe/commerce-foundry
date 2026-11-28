package com.commercefoundry.domainlayer.settlement.application.exception;

/** 정산 요청 DTO 의 필드별 검증 코드 ({@code coding-conventions.md} §8-2). 날짜 범위(오늘 기준)는 시계가 필요해 Processor 가 {@code SETTLEMENT_102} 로 본다. */
public final class SettlementValidationMessage {

    public static final String SOLD_DATE_REQUIRED = "SETTLEMENT_101:매출일은 필수입니다.";

    private SettlementValidationMessage() {
    }
}
