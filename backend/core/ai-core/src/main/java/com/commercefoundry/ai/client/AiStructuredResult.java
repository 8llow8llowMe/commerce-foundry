package com.commercefoundry.ai.client;

import java.util.Objects;

/**
 * 구조화 호출 한 번의 결과. 값은 스키마 · 업무 검증을 통과했다.
 *
 * @param value      파싱한 결과
 * @param model      실제로 답한 모델 ID
 * @param usage      토큰 사용량(캐시 토큰 포함)
 * @param stopReason 멈춘 이유 — 통과한 결과는 {@code max_tokens} 가 아니다
 * @param messageId  API 의 메시지 ID
 */
public record AiStructuredResult<T>(T value, String model, AiUsage usage, String stopReason, String messageId) {

    public AiStructuredResult {
        Objects.requireNonNull(usage, "usage");
    }

    /** 캐시 토큰 없이 입력 · 출력 토큰만 알 때(테스트 · 가짜 포트). */
    public AiStructuredResult(T value, String model, long inputTokens, long outputTokens, String stopReason, String messageId) {
        this(value, model, AiUsage.of(inputTokens, outputTokens), stopReason, messageId);
    }

    /** 입력 토큰 수(캐시 제외). */
    public long inputTokens() {
        return usage.inputTokens();
    }

    /** 출력 토큰 수. */
    public long outputTokens() {
        return usage.outputTokens();
    }

    /** 응답 값을 로그로 내지 않는다 — 고객 정보를 되풀이할 수 있다. 타입만 싣는다. */
    @Override
    public String toString() {
        return "AiStructuredResult[valueType=" + (value == null ? "null" : value.getClass().getSimpleName()) + ", model=" + model
            + ", usage=" + usage + ", stopReason=" + stopReason + ", messageId=" + messageId + "]";
    }
}
