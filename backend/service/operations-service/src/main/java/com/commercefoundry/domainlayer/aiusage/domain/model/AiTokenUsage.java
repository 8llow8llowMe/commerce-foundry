package com.commercefoundry.domainlayer.aiusage.domain.model;

/**
 * 호출 한 번의 실측 토큰 사용량 (ai-core {@code AiUsage} 를 계량 어댑터가 옮겨 담는다).
 *
 * @param inputTokens              캐시를 거치지 않은 입력
 * @param outputTokens             출력
 * @param cacheCreationInputTokens 캐시에 새로 쓴 입력
 * @param cacheReadInputTokens     캐시에서 읽은 입력
 */
public record AiTokenUsage(long inputTokens, long outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {

    public static final AiTokenUsage ZERO = new AiTokenUsage(0, 0, 0, 0);

    public AiTokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || cacheCreationInputTokens < 0 || cacheReadInputTokens < 0) {
            throw new IllegalArgumentException("토큰 수는 음수일 수 없습니다.");
        }
    }
}
