package com.commercefoundry.redis.config;

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
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class RedisStartupGuardTest {

    private static final String PASSWORD = "spring.data.redis.password=test-password";
    private static final String TIMEOUT = "spring.data.redis.timeout=1s";
    private static final String CONNECT_TIMEOUT = "spring.data.redis.connect-timeout=2s";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(systemEnvironment(Map.of()))
        .withUserConfiguration(RedisConfigurer.class);

    @Test
    @DisplayName("비밀번호 · 명령 타임아웃 · 연결 타임아웃이 있으면 기동한다")
    void startsWithValidSettings() {
        runner.withPropertyValues(PASSWORD, TIMEOUT, CONNECT_TIMEOUT)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RedisStartupGuard.class);
            });
    }

    @Test
    @DisplayName("환경변수 SPRING_DATA_REDIS_* 도 Boot 와 같은 규칙(relaxed binding)으로 읽는다")
    void readsEnvironmentVariables() {
        runner.withInitializer(systemEnvironment(Map.of(
                "SPRING_DATA_REDIS_PASSWORD", "test-password",
                "SPRING_DATA_REDIS_TIMEOUT", "1s",
                "SPRING_DATA_REDIS_CONNECTTIMEOUT", "2s")))
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("비밀번호가 없으면 기동하지 않고 프로퍼티 이름과 REDIS_PASSWORD 를 알려준다")
    void failsWithoutPassword() {
        runner.withPropertyValues(TIMEOUT, CONNECT_TIMEOUT)
            .run(context -> assertStartupFailure(context, "spring.data.redis.password", "REDIS_PASSWORD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("비밀번호가 비었거나 공백뿐이면 기동하지 않는다")
    void failsWithBlankPassword(String password) {
        runner.withInitializer(properties(Map.of("spring.data.redis.password", password)))
            .withPropertyValues(TIMEOUT, CONNECT_TIMEOUT)
            .run(context -> assertStartupFailure(context, "spring.data.redis.password", "REDIS_PASSWORD"));
    }

    @Test
    @DisplayName("환경변수가 없어 ${REDIS_PASSWORD} 가 풀리지 않으면 기동하지 않는다 — Binder 는 문자 그대로 남긴다")
    void failsWithUnresolvedPasswordPlaceholder() {
        runner.withPropertyValues("spring.data.redis.password=${REDIS_PASSWORD}", TIMEOUT, CONNECT_TIMEOUT)
            .run(context -> assertStartupFailure(context, "spring.data.redis.password", "REDIS_PASSWORD"));
    }

    @Test
    @DisplayName("명령 타임아웃이 없으면 기동하지 않는다")
    void failsWithoutTimeout() {
        runner.withPropertyValues(PASSWORD, CONNECT_TIMEOUT)
            .run(context -> assertStartupFailure(context, "spring.data.redis.timeout", "SPRING_DATA_REDIS_TIMEOUT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0s", "-1s"})
    @DisplayName("명령 타임아웃이 0 이하면 기동하지 않는다")
    void failsWithNonPositiveTimeout(String timeout) {
        runner.withPropertyValues(PASSWORD, "spring.data.redis.timeout=" + timeout, CONNECT_TIMEOUT)
            .run(context -> assertStartupFailure(context, "spring.data.redis.timeout"));
    }

    @Test
    @DisplayName("연결 타임아웃이 없으면 기동하지 않는다")
    void failsWithoutConnectTimeout() {
        runner.withPropertyValues(PASSWORD, TIMEOUT)
            .run(context -> assertStartupFailure(context, "spring.data.redis.connect-timeout", "SPRING_DATA_REDIS_CONNECTTIMEOUT"));
    }

    @Test
    @DisplayName("연결 타임아웃이 0 이면 기동하지 않는다")
    void failsWithZeroConnectTimeout() {
        runner.withPropertyValues(PASSWORD, TIMEOUT, "spring.data.redis.connect-timeout=0s")
            .run(context -> assertStartupFailure(context, "spring.data.redis.connect-timeout"));
    }

    @Test
    @DisplayName("url 이 있으면 기동하지 않는다 — host · port · password 가 무시된다. 메시지에 url · 비밀번호 값을 넣지 않는다")
    void failsWithUrl() {
        runner.withPropertyValues(PASSWORD, TIMEOUT, CONNECT_TIMEOUT, "spring.data.redis.url=redis://:url-secret@localhost:6379")
            .run(context -> {
                assertStartupFailure(context, "spring.data.redis.url", "SPRING_DATA_REDIS_URL", "REDIS_HOST");
                assertThat(context.getStartupFailure().getMessage()).doesNotContain("url-secret", "test-password");
            });
    }

    @Test
    @DisplayName("환경변수 SPRING_DATA_REDIS_URL 로 들어온 url 도 막는다")
    void failsWithUrlFromEnvironmentVariable() {
        runner.withInitializer(systemEnvironment(Map.of("SPRING_DATA_REDIS_URL", "redis://localhost:6379")))
            .withPropertyValues(PASSWORD, TIMEOUT, CONNECT_TIMEOUT)
            .run(context -> assertStartupFailure(context, "spring.data.redis.url"));
    }

    @Test
    @DisplayName("어긋난 항목을 한 번에 모두 알려준다")
    void reportsAllViolationsAtOnce() {
        runner.run(context -> assertStartupFailure(context,
            "spring.data.redis.password", "spring.data.redis.timeout", "spring.data.redis.connect-timeout"));
    }

    private static void assertStartupFailure(AssertableApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContainingAll(messageParts);
    }

    /** 개발 PC 의 실제 환경변수(SPRING_DATA_REDIS_* · REDIS_PASSWORD)가 결과를 바꾸지 않게 갈아 끼운다. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> systemEnvironment(Map<String, Object> variables) {
        String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        return context -> context.getEnvironment().getPropertySources().replace(name, new SystemEnvironmentPropertySource(name, variables));
    }

    /** {@code withPropertyValues} 는 값의 앞뒤 공백을 지운다. 공백뿐인 값은 이렇게 넣는다. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> properties(Map<String, Object> values) {
        return context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-raw", values));
    }
}
