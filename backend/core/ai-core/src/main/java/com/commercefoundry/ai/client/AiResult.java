package com.commercefoundry.ai.client;

import java.util.Objects;

/**
 * 호출 한 번의 결과. 사용량은 계량 인터셉터({@code AiCallInterceptor})가 원장에 남긴다 — 호출자가 다시 기록하지 않는다.
 *
 * @param text       응답 텍스트 블록을 이어 붙인 것
 * @param model      실제로 답한 모델 ID — 거절 대체(fallback)가 일어나면 요청한 모델과 다를 수 있다
 * @param usage      토큰 사용량(캐시 토큰 포함)
 * @param stopReason 멈춘 이유({@code end_turn} · {@code max_tokens} 등)
 * @param messageId  API 의 메시지 ID — 장애 문의 때 대조용이다. 개인정보가 아니다
 */
public record AiResult(String text, String model, AiUsage usage, String stopReason, String messageId) {

    public static final String STOP_MAX_TOKENS = "max_tokens";

    public AiResult {
        Objects.requireNonNull(usage, "usage");
    }

    /** 캐시 토큰 없이 입력 · 출력 토큰만 알 때(테스트 · 가짜 포트). */
    public AiResult(String text, String model, long inputTokens, long outputTokens, String stopReason, String messageId) {
        this(text, model, AiUsage.of(inputTokens, outputTokens), stopReason, messageId);
    }

    /** 입력 토큰 수(캐시 제외). */
    public long inputTokens() {
        return usage.inputTokens();
    }

    /** 출력 토큰 수. */
    public long outputTokens() {
        return usage.outputTokens();
    }

    /** 최대 토큰에서 잘렸다 — 결과가 끝까지 오지 않았다. 구조화 결과면 파싱이 실패할 수 있다. */
    public boolean truncated() {
        return STOP_MAX_TOKENS.equals(stopReason);
    }

    /** 응답 원문을 로그로 내지 않는다 — 고객 정보를 되풀이할 수 있다. 길이만 싣는다. */
    @Override
    public String toString() {
        return "AiResult[textLength=" + text.length() + ", model=" + model + ", usage=" + usage + ", stopReason=" + stopReason + ", messageId=" + messageId + "]";
    }
}
