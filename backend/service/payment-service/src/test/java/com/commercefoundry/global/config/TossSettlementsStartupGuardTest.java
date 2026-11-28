package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** 토스 정산 조회 클라이언트 기동 가드 (#219) — 타임아웃 하한(read 60초) · 로그 수준. */
class TossSettlementsStartupGuardTest {

    private static final String CONNECT_TIMEOUT = "spring.cloud.openfeign.client.config.toss-settlements.connect-timeout=2000";
    private static final String READ_TIMEOUT = "spring.cloud.openfeign.client.config.toss-settlements.read-timeout=60000";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(emptySystemEnvironment())
        .withUserConfiguration(GuardConfig.class);

    @Test
    @DisplayName("connect 2000 · read 60000 이면 기동한다")
    void startsWithRecommendedTimeouts() {
        runner.withPropertyValues(CONNECT_TIMEOUT, READ_TIMEOUT).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TossSettlementsStartupGuard.class);
        });
    }

    @Test
    @DisplayName("read 가 60000ms 보다 짧으면 기동하지 않는다 — 승인 클라이언트의 6000 을 옮겨 적은 실수도 막는다")
    void failsWhenReadShorterThanRecommended() {
        runner.withPropertyValues(CONNECT_TIMEOUT, "spring.cloud.openfeign.client.config.toss-settlements.read-timeout=59999")
            .run(context -> assertStartupFailure(context, "toss-settlements.read-timeout", "60000"));
    }

    @Test
    @DisplayName("타임아웃이 없으면 기동하지 않는다 — 둘 다 한 번에 알린다")
    void failsWhenTimeoutsMissing() {
        runner.run(context -> assertStartupFailure(context, "toss-settlements.connect-timeout", "toss-settlements.read-timeout"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("타임아웃이 0 이하면 기동하지 않는다")
    void failsWhenTimeoutNotPositive(String millis) {
        runner.withPropertyValues(READ_TIMEOUT, "spring.cloud.openfeign.client.config.toss-settlements.connect-timeout=" + millis)
            .run(context -> assertStartupFailure(context, "toss-settlements.connect-timeout"));
    }

    @Test
    @DisplayName("형식이 틀린 타임아웃은 값을 싣지 않고 이름만 알린다")
    void failsOnMalformedTimeoutWithoutEchoingValue() {
        runner.withPropertyValues(CONNECT_TIMEOUT, "spring.cloud.openfeign.client.config.toss-settlements.read-timeout=sixty-seconds")
            .run(context -> {
                assertStartupFailure(context, "toss-settlements.read-timeout");
                assertThat(context.getStartupFailure().getMessage()).doesNotContain("sixty-seconds");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"FULL", "headers"})
    @DisplayName("logger-level 이 HEADERS · FULL 이면 기동하지 않는다 — Authorization(시크릿 키)이 로그에 남는다")
    void failsOnHeaderLogging(String level) {
        runner.withPropertyValues(CONNECT_TIMEOUT, READ_TIMEOUT, "spring.cloud.openfeign.client.config.toss-settlements.logger-level=" + level)
            .run(context -> assertStartupFailure(context, "toss-settlements.logger-level"));
    }

    private static void assertStartupFailure(AssertableApplicationContext context, String... expectedFragments) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(failure.getMessage()).contains(expectedFragments);
    }

    /** OS 환경변수가 결과를 바꾸지 않게 비운다. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> emptySystemEnvironment() {
        String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        return context -> context.getEnvironment().getPropertySources().replace(name, new SystemEnvironmentPropertySource(name, Map.of()));
    }

    static class GuardConfig {

        @Bean
        static TossSettlementsStartupGuard tossSettlementsStartupGuard(Environment environment) {
            return new TossSettlementsStartupGuard(environment);
        }
    }
}
