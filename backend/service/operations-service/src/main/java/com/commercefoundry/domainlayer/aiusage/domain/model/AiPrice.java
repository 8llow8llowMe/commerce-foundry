package com.commercefoundry.domainlayer.aiusage.domain.model;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

/**
 * 모델 하나의 토큰 단가 — USD / 1M 토큰(MTok). 이진 오차가 없게 {@link BigDecimal} 로만 들고 다닌다 ({@code coding-conventions.md} §9-3).
 *
 * @param input      캐시를 거치지 않은 입력
 * @param output     출력
 * @param cacheWrite 캐시에 새로 쓴 입력
 * @param cacheRead  캐시에서 읽은 입력
 */
public record AiPrice(BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {

    public AiPrice {
        requireNonNegative(input, "input");
        requireNonNegative(output, "output");
        requireNonNegative(cacheWrite, "cacheWrite");
        requireNonNegative(cacheRead, "cacheRead");
    }

    /** 항목별 최댓값 — 설정에 없는 모델을 가장 비싸게 계산할 때 쓴다. 한 모델의 단가가 아니라 항목마다 따로 고른 값이다. */
    public static AiPrice maxOf(Collection<AiPrice> prices) {
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("단가가 하나도 없습니다.");
        }
        return new AiPrice(max(prices, AiPrice::input), max(prices, AiPrice::output), max(prices, AiPrice::cacheWrite), max(prices, AiPrice::cacheRead));
    }

    private static BigDecimal max(Collection<AiPrice> prices, Function<AiPrice, BigDecimal> item) {
        return prices.stream().map(item).reduce(BigDecimal::max).orElseThrow();
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " 단가는 음수일 수 없습니다: " + value);
        }
    }
}
