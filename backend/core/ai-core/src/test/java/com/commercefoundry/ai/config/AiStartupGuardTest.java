package com.commercefoundry.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiStartupGuardTest {

    private static final String KEY = "sk-test-guard-probe";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AiPropertiesConfig.class, AiConfigurer.class);

    @Test
    @DisplayName("ai.* 가 하나도 없으면 꺼진 채 기동하고, 클라이언트는 DISABLED 다")
    void startsDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(AiClient.class).complete(AiRequest.of("size-chart", null, "prompt")))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(AiException.Reason.DISABLED));
        });
    }

    @Test
    @DisplayName("꺼져 있으면 Vault 가 비어 ${AI_API_KEY} 가 풀리지 않아도 기동한다")
    void startsDisabledWithUnresolvedPlaceholder() {
        runner.withPropertyValues("ai.api-key=${AI_API_KEY}").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("켜면 키 · 타임아웃이 모두 있어야 기동한다")
    void startsEnabled() {
        runner.withPropertyValues(enabled()).run(context -> assertThat(context).hasNotFailed().hasSingleBean(AiClient.class));
    }

    @Test
    @DisplayName("켰는데 키 · 타임아웃이 없으면 기동을 멈추고 이름만 알린다")
    void failsWithoutRequiredValues() {
        runner.withPropertyValues("ai.enabled=true", "ai.api-key=${AI_API_KEY}").run(context -> assertThat(context).hasFailed().getFailure()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ai.api-key(환경변수 AI_API_KEY)")
            .hasMessageContaining("ai.connect-timeout").hasMessageContaining("ai.read-timeout").hasMessageContaining("ai.request-timeout"));
    }

    @Test
    @DisplayName("http base-url 은 루프백만 허용한다 — 메시지에 주소 · 키를 싣지 않는다")
    void rejectsPlainHttpBaseUrl() {
        runner.withPropertyValues(enabled()).withPropertyValues("ai.base-url=http://ai-proxy.example.test")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("ai.base-url").message().doesNotContain("ai-proxy.example.test").doesNotContain(KEY));
    }

    @Test
    @DisplayName("루프백 http 도 allow-insecure-loopback 없이는 막는다 — 운영은 https 만. 켜면(테스트 스텁) 허용한다")
    void loopbackHttpNeedsFlag() {
        runner.withPropertyValues(enabled()).withPropertyValues("ai.base-url=http://127.0.0.1:8089")
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining("ai.base-url"));
        runner.withPropertyValues(enabled()).withPropertyValues("ai.base-url=http://127.0.0.1:8089", "ai.allow-insecure-loopback=true")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("기본 패턴과 이름이 겹치는 마스킹 패턴(email 등)은 막는다 — 조용히 무시되지 않게")
    void rejectsReservedPatternName() {
        runner.withPropertyValues(enabled()).withPropertyValues("ai.masking.patterns.email=x@y")
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining("ai.masking.patterns.email"));
    }

    @Test
    @DisplayName("거절 대체(refusal-fallback)는 기본 꺼짐이다 — 닫힌 기본값")
    void refusalFallbackIsOffByDefault() {
        runner.withPropertyValues(enabled())
            .run(context -> assertThat(context.getBean(com.commercefoundry.ai.properties.AiProperties.class).refusalFallback()).isFalse());
    }

    @Test
    @DisplayName("형식이 틀린 시간은 이름만 알린다 — Boot 바인딩 오류의 값이 메시지에 없다")
    void malformedDuration() {
        runner.withPropertyValues(enabled()).withPropertyValues("ai.read-timeout=ten-seconds")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ai.read-timeout").message().doesNotContain("ten-seconds"));
    }

    @Test
    @DisplayName("기능 effort · 마스킹 정규식이 틀리면 기동을 멈춘다 — 패턴 원문을 싣지 않는다")
    void rejectsFeatureAndPattern() {
        runner.withPropertyValues(enabled()).withPropertyValues("ai.features.size-chart.effort=extreme", "ai.masking.patterns.order-number=CF-[0-9")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("ai.features.size-chart.effort").hasMessageContaining("ai.masking.patterns.order-number")
                .message().doesNotContain("CF-[0-9"));
    }

    @Test
    @DisplayName("재시도 · 동시 상한 범위를 본다")
    void rejectsLimits() {
        runner.withPropertyValues(enabled()).withPropertyValues("ai.max-retries=9", "ai.max-concurrency=0")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("ai.max-retries").hasMessageContaining("ai.max-concurrency"));
    }

    private static String[] enabled() {
        return new String[] {"ai.enabled=true", "ai.api-key=" + KEY, "ai.connect-timeout=2s", "ai.read-timeout=120s", "ai.request-timeout=180s"};
    }
}
