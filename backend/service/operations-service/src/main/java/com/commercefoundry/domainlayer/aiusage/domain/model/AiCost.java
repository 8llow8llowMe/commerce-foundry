package com.commercefoundry.domainlayer.aiusage.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * AI 호출 비용 계산 — 결과는 micro-USD({@code long}, 1 USD = 1,000,000). 통화는 언제나 {@link #CURRENCY} 다.
 *
 * <p>비용(USD) = Σ 토큰 × 단가(USD/MTok) / 1,000,000 이므로 micro-USD 로는 Σ 토큰 × 단가 그대로다. 항목별로 반올림하지 않고 합계를 한 번
 * <b>올림(CEILING)</b> 한다 — 한도를 지키는 쪽으로 기운다. 원 단위 금액이 아니라 Money({@code shared-commerce}, 센트 단위)를 쓰지 않는다 —
 * 호출 한 번은 대개 1센트보다 작다.
 */
public final class AiCost {

    public static final String CURRENCY = "USD";

    private AiCost() {
    }

    /** 실측 사용량의 비용. */
    public static long micros(AiTokenUsage usage, AiPrice price) {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(price, "price");
        BigDecimal total = price.input().multiply(BigDecimal.valueOf(usage.inputTokens()))
            .add(price.output().multiply(BigDecimal.valueOf(usage.outputTokens())))
            .add(price.cacheWrite().multiply(BigDecimal.valueOf(usage.cacheCreationInputTokens())))
            .add(price.cacheRead().multiply(BigDecimal.valueOf(usage.cacheReadInputTokens())));
        return total.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /** 보내기 전 예상 비용 — 예상 입력 토큰은 입력 단가로, 최대 출력 토큰 전부는 출력 단가로 계산한다(캐시 할인을 기대하지 않는다). */
    public static long estimateMicros(AiEstimatePolicy policy, long inputChars, int imageCount, int maxTokens, AiPrice price) {
        Objects.requireNonNull(policy, "policy");
        return micros(policy.estimate(inputChars, imageCount, maxTokens), price);
    }

    /** USD 금액(예 {@code "50"}, {@code "12.5"})을 micro-USD 로 바꾼다. 백만분의 1 아래 자릿수는 올림이 아니라 거절한다 — 설정 실수를 숨기지 않게. */
    public static long usdToMicros(BigDecimal usd) {
        Objects.requireNonNull(usd, "usd");
        return usd.movePointRight(6).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
}
