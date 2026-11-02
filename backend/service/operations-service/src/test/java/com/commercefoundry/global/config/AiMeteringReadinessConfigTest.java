package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.global.properties.AiUsageProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * AI 를 켰을 때만 월 한도 · 단가 · 회수 시각을 본다 — 꺼져 있으면 비어도 기동한다. 실제 바인딩(yml 키 이름)으로 컨텍스트를 띄워 기동 성공 · 실패를 확인한다.
 * 값은 테스트 전용이다.
 */
class AiMeteringReadinessConfigTest {

    private static final String[] READY = {
        "operations.ai.budget.monthly-usd=50", "operations.ai.pricing.version=v1",
        "operations.ai.pricing.models.[model-a].input=1", "operations.ai.pricing.models.[model-a].output=5",
        "operations.ai.pricing.models.[model-a].cache-write=1.25", "operations.ai.pricing.models.[model-a].cache-read=0.1"
    };

    /** 기본 AI 설정 — 최악 시간 (1 + 2) × 240s + 10s = 730s, 여유 1분을 더하면 790s(13분 10초). */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Bindings.class, AiMeteringReadinessConfig.class)
        .withPropertyValues(
            "ai.request-timeout=240s", "ai.max-retries=2", "ai.acquire-timeout=10s",
            "operations.ai.budget.zone=Asia/Seoul", "operations.ai.budget.warn-ratio=0.8",
            "operations.ai.estimate.chars-per-token=2", "operations.ai.estimate.tokens-per-image=1600",
            "operations.ai.pricing.version=", "operations.ai.reservation-sweep.stale-after=15m", "operations.ai.reservation-sweep.batch-size=100",
            "operations.ai.retention.days=400", "operations.ai.retention.batch-size=500", "operations.ai.retention.max-batches-per-run=20"
        );

    @Test
    @DisplayName("꺼져 있으면 월 한도 · 단가가 비고 회수 시각이 짧아도 기동한다")
    void disabledAllowsEmpty() {
        runner.withPropertyValues("ai.enabled=false", "operations.ai.reservation-sweep.stale-after=1s")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("켜져 있고 월 한도 · 단가가 있고 회수 시각이 최악 시간 + 여유보다 길면 기동한다")
    void enabledAndReadyStarts() {
        runner.withPropertyValues("ai.enabled=true").withPropertyValues(READY)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("켜져 있는데 월 한도 · 단가가 비면 기동하지 않고 키 이름으로 알린다")
    void enabledWithoutBudgetFails() {
        runner.withPropertyValues("ai.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("operations.ai.budget.monthly-usd")
                .hasMessageContaining("AI_MONTHLY_BUDGET_USD")
                .hasMessageContaining("operations.ai.pricing.models");
        });
    }

    @Test
    @DisplayName("켜져 있는데 회수 시각이 최악 시간 + 백오프 여유(13분 10초) 이하면 기동하지 않는다 — 진행 중인 호출을 회수하게 된다")
    void staleAfterTooShortFails() {
        runner.withPropertyValues("ai.enabled=true", "operations.ai.reservation-sweep.stale-after=790s").withPropertyValues(READY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("operations.ai.reservation-sweep.stale-after")
                .hasMessageNotContaining("790");
        });
        runner.withPropertyValues("ai.enabled=true", "operations.ai.reservation-sweep.stale-after=791s").withPropertyValues(READY)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("재시도를 늘리면 최악 시간도 는다 — 재시도 5 · 요청 240s 면 기본 15분으로는 모자란다")
    void moreRetriesNeedLongerStaleAfter() {
        runner.withPropertyValues("ai.enabled=true", "ai.max-retries=5").withPropertyValues(READY)
            .run(context -> assertThat(context).hasFailed());
        assertThat(AiMeteringReadinessConfig.BACKOFF_MARGIN).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void openAiRequiresExplicitPricesIncludingFeatureOverrides() {
        runner.withPropertyValues("ai.enabled=true", "ai.provider=openai", "ai.openai.model=model-b").withPropertyValues(READY)
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ai.enabled=true", "ai.provider=openai", "ai.openai.model=model-a").withPropertyValues(READY)
            .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("ai.enabled=true", "ai.features.chat.provider=openai", "ai.openai.model=model-a",
            "ai.features.chat.model=model-b").withPropertyValues(READY)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test void providerOnlyPricesBindAndRequireEveryUsedProviderAndExplicitOpenAiModel() {
        var namespace = runner.withPropertyValues("operations.ai.budget.monthly-usd=50", "operations.ai.pricing.version=v1",
            "operations.ai.pricing.providers.openai.models.[model-a].input=2", "operations.ai.pricing.providers.openai.models.[model-a].output=10",
            "operations.ai.pricing.providers.openai.models.[model-a].cache-write=3", "operations.ai.pricing.providers.openai.models.[model-a].cache-read=0.2");
        namespace.withPropertyValues("ai.enabled=true", "ai.provider=openai", "ai.openai.model=model-a").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiUsageProperties.class).pricing().toTable().quote("openai", "model-a").price().input()).isEqualByComparingTo("2");
        });
        namespace.withPropertyValues("ai.enabled=true", "ai.provider=openai", "ai.openai.model=unregistered").run(context -> assertThat(context).hasFailed());
        namespace.withPropertyValues("ai.enabled=true", "ai.features.chat.provider=openai", "ai.openai.model=model-a")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiProperties.class, AiUsageProperties.class})
    static class Bindings {

    }
}
