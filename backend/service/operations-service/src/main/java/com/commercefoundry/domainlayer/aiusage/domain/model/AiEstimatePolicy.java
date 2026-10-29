package com.commercefoundry.domainlayer.aiusage.domain.model;

/**
 * 보내기 전 예상 비용의 토큰 환산 규칙. 토큰 수를 미리 셀 수 없어 글자 수 · 이미지 장수로 어림한다 — 한국어는 글자당 토큰이 많아 보수적으로 잡는다.
 *
 * @param charsPerToken  입력 글자 몇 개를 토큰 하나로 칠지 (올림)
 * @param tokensPerImage 이미지 한 장을 입력 토큰 몇 개로 칠지
 */
public record AiEstimatePolicy(int charsPerToken, int tokensPerImage) {

    public AiEstimatePolicy {
        if (charsPerToken <= 0) {
            throw new IllegalArgumentException("charsPerToken 은 1 이상이어야 합니다: " + charsPerToken);
        }
        if (tokensPerImage < 0) {
            throw new IllegalArgumentException("tokensPerImage 는 음수일 수 없습니다: " + tokensPerImage);
        }
    }

    /** 예상 입력 토큰 = ceil(글자 수 / charsPerToken) + 이미지 장수 × tokensPerImage. */
    public AiTokenUsage estimate(long inputChars, int imageCount, int maxTokens) {
        if (inputChars < 0 || imageCount < 0 || maxTokens < 0) {
            throw new IllegalArgumentException("글자 수 · 이미지 장수 · 최대 출력 토큰은 음수일 수 없습니다.");
        }
        long textTokens = Math.ceilDiv(inputChars, (long) charsPerToken);
        long imageTokens = Math.multiplyExact((long) imageCount, (long) tokensPerImage);
        return new AiTokenUsage(Math.addExact(textTokens, imageTokens), maxTokens, 0, 0);
    }
}
