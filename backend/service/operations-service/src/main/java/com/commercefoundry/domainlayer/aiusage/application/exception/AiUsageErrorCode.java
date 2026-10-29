package com.commercefoundry.domainlayer.aiusage.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * AI 사용량 원장 오류 코드 ({@code coding-conventions.md} §8). 이 컨텍스트에는 HTTP 진입이 없다 — 계량 어댑터(PR-B3)가 ai-core {@code AiException} 으로 번역하고,
 * 작업 핸들러가 작업 실패 코드로 남긴다. 상태 코드는 진입이 생길 때를 위한 값이다.
 */
@Getter
@RequiredArgsConstructor
public enum AiUsageErrorCode {

    /** 월 한도를 넘는 예약이다 — 같은 달에는 다시 시도해도 통과하지 않는다. ai-core {@code BUDGET_EXCEEDED}(재시도 없음)로 번역한다. */
    BUDGET_EXCEEDED("AI_USAGE_001", "이번 달 AI 사용 한도를 넘어 요청하지 않았습니다.", HttpStatus.TOO_MANY_REQUESTS),
    /**
     * 월 카운터를 두고 다른 예약 · 정산과 엇갈려 정해진 횟수 안에 예약을 판정하지 못했다 — 일시적 DB 경합이다. 계량 어댑터가 ai-core
     * {@code METERING_FAILED}(재시도)로 번역한다.
     */
    RESERVE_CONTENDED("AI_USAGE_020", "AI 사용량 예약이 다른 요청과 겹쳤습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
