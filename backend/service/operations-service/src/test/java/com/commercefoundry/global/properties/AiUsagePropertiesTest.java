package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiPricedBy;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiUsagePropertiesTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final AiUsageProperties.Pricing.Price PRICE = new AiUsageProperties.Pricing.Price("3", "15", "3.75", "0.30");

    /** application.yml 과 같은 모양 — 환경변수가 없을 때의 빈 기본값({@code ${...:}})이 실제 바인딩에서 무엇이 되는지 본다. */
    @Test
    @DisplayName("yml 기본값처럼 월 한도 · 알림 대상 환경변수가 비면 바인딩 결과는 한도 없음(null) · 빈 목록이고, 쉼표 목록은 아이디 목록이 된다")
    void bindsEmptyEnvironmentDefaults() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class)
            .withPropertyValues(
                "operations.ai.budget.monthly-usd=${AI_USAGE_TEST_UNSET_BUDGET:}",
                "operations.ai.budget.zone=Asia/Seoul",
                "operations.ai.budget.warn-ratio=0.8",
                "operations.ai.budget.notify-member-ids=${AI_USAGE_TEST_UNSET_MEMBERS:}",
                "operations.ai.estimate.chars-per-token=2",
                "operations.ai.estimate.tokens-per-image=1600",
                "operations.ai.pricing.version=",
                "operations.ai.reservation-sweep.stale-after=15m",
                "operations.ai.reservation-sweep.batch-size=100",
                "operations.ai.retention.days=400",
                "operations.ai.retention.batch-size=500",
                "operations.ai.retention.max-batches-per-run=20"
            );

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AiUsageProperties properties = context.getBean(AiUsageProperties.class);
            assertThat(properties.budget().monthlyUsd()).isNull();
            assertThat(properties.budget().limitMicros()).isEmpty();
            assertThat(properties.budget().notifyMemberIds()).isEmpty();
            assertThat(properties.pricing().toTable().isEmpty()).isTrue();
        });
        runner.withPropertyValues("operations.ai.budget.monthly-usd=50", "operations.ai.budget.notify-member-ids=7, 9", "operations.ai.pricing.version=v1",
                "operations.ai.pricing.models.[claude-x.1].input=3", "operations.ai.pricing.models.[claude-x.1].output=15",
                "operations.ai.pricing.models.[claude-x.1].cache-write=3.75", "operations.ai.pricing.models.[claude-x.1].cache-read=0.3")
            .run(context -> {
                AiUsageProperties properties = context.getBean(AiUsageProperties.class);
                assertThat(properties.budget().limitMicros()).hasValue(50_000_000L);
                assertThat(properties.budget().notifyMemberIds()).containsExactly(7L, 9L);
                assertThat(properties.pricing().toTable().quote("claude-x.1").pricedBy()).as("대괄호 키는 점을 지킨다").isEqualTo(AiPricedBy.CONFIG);
            });
    }

    @EnableConfigurationProperties(AiUsageProperties.class)
    static class BindingConfig {
    }

    @Test
    @DisplayName("월 한도 USD 를 micro-USD 로 바꾸고, 경고 임계는 한도 × 비율을 올림한다")
    void budgetConvertsToMicros() {
        AiUsageProperties.Budget budget = new AiUsageProperties.Budget("12.5", SEOUL, "0.8", List.of(7L));

        assertThat(budget.limitMicros()).hasValue(12_500_000L);
        assertThat(budget.warnThresholdMicros(12_500_000L)).isEqualTo(10_000_000L);
        assertThat(budget.warnThresholdMicros(3L)).as("2.4 → 3").isEqualTo(3L);
        assertThat(budget.notifyMemberIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("월 한도 · 알림 대상이 비어 있어도 기동한다 — 한도는 '설정 안 됨' 이다(AI 를 켤 때의 가드는 PR-B3)")
    void emptyBudgetIsAllowed() {
        AiUsageProperties.Budget budget = new AiUsageProperties.Budget("", SEOUL, "0.8", null);

        assertThat(budget.limitMicros()).isEmpty();
        assertThat(budget.notifyMemberIds()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "1.5", "-0.1", "abc", ""})
    @DisplayName("경고 비율은 0 초과 1 미만 숫자여야 한다")
    void warnRatioMustBeBetweenZeroAndOne(String ratio) {
        assertThatThrownBy(() -> new AiUsageProperties.Budget("10", SEOUL, ratio, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("warn-ratio");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0000001", "ten"})
    @DisplayName("월 한도는 양수이고 소수 6자리 이하여야 한다")
    void monthlyUsdMustBePositiveMicros(String usd) {
        assertThatThrownBy(() -> new AiUsageProperties.Budget(usd, SEOUL, "0.8", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("monthly-usd");
    }

    @Test
    @DisplayName("알림 대상 아이디는 양수여야 한다")
    void notifyMemberIdsMustBePositive() {
        assertThatThrownBy(() -> new AiUsageProperties.Budget("10", SEOUL, "0.8", List.of(0L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("notify-member-ids");
    }

    @Test
    @DisplayName("단가는 문자열 → BigDecimal 로 바꿔 단가표가 된다")
    void pricingBuildsTable() {
        AiUsageProperties.Pricing pricing = new AiUsageProperties.Pricing("2026-10", Map.of("model-a", PRICE));

        assertThat(pricing.toTable().quote("model-a").pricedBy()).isEqualTo(AiPricedBy.CONFIG);
        assertThat(pricing.toTable().quote("model-a").price().cacheRead()).isEqualByComparingTo("0.3");
        assertThat(new AiUsageProperties.Pricing("", Map.of()).toTable().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("단가는 음수 · 빈 값 · 숫자 아님을 거절하고, 단가가 있으면 버전이 있어야 한다")
    void pricingRejectsInvalidValues() {
        assertThatThrownBy(() -> pricing(new AiUsageProperties.Pricing.Price("-1", "15", "3.75", "0.3")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input");
        assertThatThrownBy(() -> pricing(new AiUsageProperties.Pricing.Price("3", "", "3.75", "0.3")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("output");
        assertThatThrownBy(() -> pricing(new AiUsageProperties.Pricing.Price("3", "15", "x", "0.3")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cache-write");
        assertThatThrownBy(() -> pricing(new AiUsageProperties.Pricing.Price("3", "15", "3.75", null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cache-read");
        assertThatThrownBy(() -> new AiUsageProperties.Pricing(null, Map.of("model-a", PRICE)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> new AiUsageProperties.Pricing("v".repeat(21), Map.of("model-a", PRICE)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
    }

    @Test
    @DisplayName("블록이 빠지거나 회수 · 보존 · 예상치 값이 틀리면 기동하지 않는다")
    void requiresAllBlocks() {
        AiUsageProperties.Budget budget = new AiUsageProperties.Budget("10", SEOUL, "0.8", List.of());
        AiUsageProperties.Estimate estimate = new AiUsageProperties.Estimate(2, 1600);
        AiUsageProperties.Pricing pricing = new AiUsageProperties.Pricing("v1", Map.of("model-a", PRICE));
        AiUsageProperties.ReservationSweep sweep = new AiUsageProperties.ReservationSweep(Duration.ofMinutes(15), 100);
        AiUsageProperties.Retention retention = new AiUsageProperties.Retention(400, 500, 20);

        assertThat(new AiUsageProperties(budget, estimate, pricing, sweep, retention).estimate().toPolicy().charsPerToken()).isEqualTo(2);
        assertThatThrownBy(() -> new AiUsageProperties(null, estimate, pricing, sweep, retention)).hasMessageContaining("operations.ai.budget");
        assertThatThrownBy(() -> new AiUsageProperties.Estimate(0, 1600)).hasMessageContaining("operations.ai.estimate");
        assertThatThrownBy(() -> new AiUsageProperties.ReservationSweep(Duration.ZERO, 100)).hasMessageContaining("stale-after");
        assertThatThrownBy(() -> new AiUsageProperties.ReservationSweep(Duration.ofMinutes(1), 0)).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new AiUsageProperties.Retention(0, 500, 20)).hasMessageContaining("operations.ai.retention");
        assertThatThrownBy(() -> new AiUsageProperties.Budget("10", null, "0.8", List.of())).hasMessageContaining("zone");
    }

    private static AiUsageProperties.Pricing pricing(AiUsageProperties.Pricing.Price price) {
        return new AiUsageProperties.Pricing("v1", Map.of("model-a", price));
    }
}
