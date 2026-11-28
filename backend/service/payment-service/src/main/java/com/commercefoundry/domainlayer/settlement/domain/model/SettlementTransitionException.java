package com.commercefoundry.domainlayer.settlement.domain.model;

import lombok.Getter;

/**
 * 허용 전이 표 밖의 정산 상태 전이 — 현재 상태와 시도한 동작을 싣는다. domain 은 오류 코드를 모른다 — Processor 가 받아 {@code SETTLEMENT_} 코드로 바꾼다
 * ({@code coding-conventions.md} §8-1).
 */
@Getter
public class SettlementTransitionException extends RuntimeException {

    private final String currentStatus;
    private final String action;

    public SettlementTransitionException(Enum<?> currentStatus, String action) {
        super("허용되지 않은 정산 상태 전이입니다. status=%s, action=%s".formatted(currentStatus, action));
        this.currentStatus = currentStatus.name();
        this.action = action;
    }
}
