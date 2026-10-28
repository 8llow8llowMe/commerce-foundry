package com.commercefoundry.ai.client;

/**
 * API 가 알려 준 호출 한 번의 토큰 사용량. 비용 계산(단가 × 토큰)은 사용량 원장을 가진 서비스 몫이다.
 *
 * <p>캐시 토큰은 API 가 싣지 않으면 0 이다 — 원장이 {@code null} 을 따로 다루지 않게 여기서 정규화한다.
 *
 * @param inputTokens              캐시를 거치지 않은 입력 토큰
 * @param outputTokens             출력 토큰
 * @param cacheCreationInputTokens 캐시에 새로 쓴 입력 토큰 — 입력 단가와 다르게 과금된다
 * @param cacheReadInputTokens     캐시에서 읽은 입력 토큰 — 입력 단가와 다르게 과금된다
 */
public record AiUsage(long inputTokens, long outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {

    public static final AiUsage ZERO = new AiUsage(0, 0, 0, 0);

    /** 캐시 토큰 없이 입력 · 출력만 알 때. */
    public static AiUsage of(long inputTokens, long outputTokens) {
        return new AiUsage(inputTokens, outputTokens, 0, 0);
    }
}
