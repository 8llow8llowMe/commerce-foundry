package com.commercefoundry.ai.client;

/**
 * 대화 호출이 멈춘 이유 — Provider 중립 어휘다. 각 Provider 클라이언트가 자기 값을 여기로 번역할 책임을 진다
 * (Claude: {@code end_turn} → {@link #END_TURN}, {@code tool_use} → {@link #TOOL_USE}, {@code max_tokens} → {@link #MAX_TOKENS},
 * {@code refusal} → {@link #REFUSAL}, 그 밖({@code stop_sequence} · {@code pause_turn} 등) → {@link #OTHER}. #468 OpenAI 는 {@code stop} · {@code tool_calls} ·
 * {@code length} · {@code content_filter} 를 같은 값으로 옮긴다). 원래 값은 로그에만 남긴다.
 */
public enum AiStopReason {

    /** 모델이 답을 마쳤다. */
    END_TURN,
    /** 모델이 도구 호출을 요청하고 멈췄다 — {@link AiConversationResult#toolCalls()} 가 비어 있지 않다. */
    TOOL_USE,
    /** 최대 출력 토큰에서 잘렸다. 텍스트만 잘린 경우에만 결과가 된다(잘린 도구 호출은 {@code INVALID_RESPONSE}). */
    MAX_TOKENS,
    /** 모델이 안전 분류로 답하지 않았다. 결과로 돌아오지 않고 {@code REFUSED} 예외다 — 번역 어휘로만 둔다. */
    REFUSAL,
    /** 스트리밍을 호출자가 취소했다 — 받은 조각만 있다. */
    CANCELLED,
    /** 그 밖의 이유. */
    OTHER;

    /** Claude Messages API 의 {@code stop_reason} 을 옮긴다. */
    static AiStopReason fromClaude(String stopReason) {
        if (stopReason == null) {
            return OTHER;
        }
        return switch (stopReason) {
            case "end_turn" -> END_TURN;
            case "tool_use" -> TOOL_USE;
            case "max_tokens" -> MAX_TOKENS;
            case "refusal" -> REFUSAL;
            default -> OTHER;
        };
    }
}
