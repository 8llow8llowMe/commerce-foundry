package com.commercefoundry.domainlayer.aiusage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiPriceTable.AiPriceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiCostTest {

    private static final AiPrice LARGE = price("5", "25", "6.25", "0.5");
    private static final AiPrice SMALL = price("1", "5", "1.25", "0.1");

    @Test
    @DisplayName("비용(micro-USD) = Σ 토큰 × 단가(USD/MTok) — 입력 · 출력 · 캐시 쓰기 · 캐시 읽기를 각 단가로 더한다")
    void costSumsEachTokenKindWithItsPrice() {
        AiTokenUsage usage = new AiTokenUsage(1_000, 200, 100, 1_000);

        // 1000×5 + 200×25 + 100×6.25 + 1000×0.5 = 5000 + 5000 + 625 + 500
        assertThat(AiCost.micros(usage, LARGE)).isEqualTo(11_125L);
        assertThat(AiCost.micros(AiTokenUsage.ZERO, LARGE)).isZero();
    }

    @Test
    @DisplayName("소수 micro 는 항목별이 아니라 합계를 한 번 올림(CEILING)한다")
    void ceilsTheTotalOnce() {
        AiPrice fractional = price("0.3", "0.3", "0", "0");

        // 0.3 + 0.3 = 0.6 → 1 (항목별 올림이면 2)
        assertThat(AiCost.micros(new AiTokenUsage(1, 1, 0, 0), fractional)).isEqualTo(1L);
        // 0.1 × 3 = 0.3 → 1
        assertThat(AiCost.micros(new AiTokenUsage(0, 0, 0, 3), SMALL)).isEqualTo(1L);
        // 0.1 × 10 = 1 — 정수면 올리지 않는다
        assertThat(AiCost.micros(new AiTokenUsage(0, 0, 0, 10), SMALL)).isEqualTo(1L);
    }

    @Test
    @DisplayName("예상치 = ceil(글자 수 / 2) + 이미지 × 1600 토큰을 입력 단가로, 최대 출력 토큰 전부를 출력 단가로")
    void estimateUsesInputCharsImagesAndMaxTokens() {
        AiEstimatePolicy policy = new AiEstimatePolicy(2, 1600);

        // 입력 ceil(1001/2)=501 + 2×1600=3200 → 3701 × 5 = 18505, 출력 1000 × 25 = 25000
        assertThat(AiCost.estimateMicros(policy, 1_001, 2, 1_000, LARGE)).isEqualTo(43_505L);
        assertThat(policy.estimate(1_001, 2, 1_000)).isEqualTo(new AiTokenUsage(3_701, 1_000, 0, 0));
        assertThat(AiCost.estimateMicros(policy, 0, 0, 0, LARGE)).isZero();
    }

    @Test
    @DisplayName("설정에 있는 모델은 CONFIG, 없는 모델은 항목별 최고 단가(MAX_FALLBACK)로 계산한다")
    void unknownModelIsPricedByItemwiseMaximum() {
        AiPrice odd = price("2", "4", "3", "0.05");
        AiPriceTable table = new AiPriceTable("v1", Map.of("small", SMALL, "odd", odd));

        AiPriceQuote known = table.quote("small");
        AiPriceQuote unknown = table.quote("unknown-model");

        assertThat(known).isEqualTo(new AiPriceQuote(SMALL, AiPricedBy.CONFIG, "v1"));
        assertThat(unknown.pricedBy()).isEqualTo(AiPricedBy.MAX_FALLBACK);
        assertThat(unknown.version()).isEqualTo("v1");
        assertThat(unknown.price()).isEqualTo(price("2", "5", "3", "0.1"));
    }

    @Test
    @DisplayName("단가가 하나도 없으면 계산하지 않는다 — 단가 있는 표에는 버전이 있어야 한다")
    void emptyTableCannotQuote() {
        AiPriceTable empty = new AiPriceTable(null, Map.of());

        assertThat(empty.isEmpty()).isTrue();
        assertThatThrownBy(() -> empty.quote("any")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiPriceTable(" ", Map.of("small", SMALL))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 단가 · 음수 토큰은 만들지 않는다")
    void rejectsNegatives() {
        assertThatThrownBy(() -> price("-1", "0", "0", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiTokenUsage(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiEstimatePolicy(0, 1600)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("USD → micro-USD 는 소수 6자리까지만 받는다 — 그 아래 자릿수는 올리지 않고 거절한다")
    void usdToMicros() {
        assertThat(AiCost.usdToMicros(new BigDecimal("12.5"))).isEqualTo(12_500_000L);
        assertThat(AiCost.usdToMicros(new BigDecimal("0.000001"))).isEqualTo(1L);
        assertThatThrownBy(() -> AiCost.usdToMicros(new BigDecimal("0.0000001"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("월 경계는 설정 시간대로 자른다 — KST 11월 1일 00:00 = 2026-10-31T15:00Z 부터 11월이다")
    void periodFollowsConfiguredZone() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");

        assertThat(AiBudgetPeriod.of(Instant.parse("2026-10-31T15:00:00Z"), seoul).value()).isEqualTo("2026-11");
        assertThat(AiBudgetPeriod.of(Instant.parse("2026-10-31T14:59:59Z"), seoul).value()).isEqualTo("2026-10");
        assertThat(AiBudgetPeriod.of(Instant.parse("2026-10-31T15:00:00Z"), ZoneOffset.UTC).value()).isEqualTo("2026-10");
        assertThatThrownBy(() -> new AiBudgetPeriod("2026-13")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new AiBudgetPeriod("2026-1")).isInstanceOf(RuntimeException.class);
    }

    private static AiPrice price(String input, String output, String cacheWrite, String cacheRead) {
        return new AiPrice(new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheWrite), new BigDecimal(cacheRead));
    }
}
