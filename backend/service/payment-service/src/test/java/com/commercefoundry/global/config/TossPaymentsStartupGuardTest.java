package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 토스 키 · 타임아웃 기동 가드. 키는 전부 형식만 흉내 낸 더미다 — 실키가 아니다 (secret-management.md §5).
 */
class TossPaymentsStartupGuardTest {

    private static final String TEST_KEY = "test_sk_dummyGuardKey0001";
    private static final String LIVE_KEY = "live_sk_dummyGuardKey0001";
    private static final String BASE_URL = "toss.base-url=https://api.tosspayments.com";
    private static final String CONNECT_TIMEOUT = "spring.cloud.openfeign.client.config.toss-payments.connect-timeout=2000";
    private static final String READ_TIMEOUT = "spring.cloud.openfeign.client.config.toss-payments.read-timeout=6000";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(emptySystemEnvironment())
        .withUserConfiguration(GuardConfig.class)
        .withPropertyValues(BASE_URL, CONNECT_TIMEOUT, READ_TIMEOUT);

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "test"})
    @DisplayName("local · dev · test 프로파일은 test_ 키로 기동한다")
    void startsWithTestKeyOnTestProfiles(String profile) {
        runWith(profile, TEST_KEY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TossPaymentsStartupGuard.class);
        });
    }

    @Test
    @DisplayName("prod 프로파일은 live_ 키로 기동한다")
    void startsWithLiveKeyOnProd() {
        runWith("prod", LIVE_KEY).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"test_sk_abc123XYZ", "test_gsk_abc123XYZ", "test_gsk_docs_dummyKey0001"})
    @DisplayName("API 개별 연동 키(sk)와 결제위젯 키(gsk)를 둘 다 받고, 문서 예시 키처럼 밑줄이 섞여도 받는다")
    void acceptsApiAndWidgetSecretKeys(String key) {
        runWith("local", key).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.tosspayments.com/", "http://api.tosspayments.com", "https://api.tosspayments.com.example.org", "http://127.0.0.1:8089"})
    @DisplayName("live_ 키면 base-url 은 정확히 https://api.tosspayments.com 이어야 한다 — 라이브 키의 인증 헤더가 다른 호스트로 나가지 않게")
    void failsWithLiveKeyOnOtherBaseUrl(String baseUrl) {
        runWith("prod", LIVE_KEY)
            .withPropertyValues("toss.base-url=" + baseUrl)
            .run(context -> assertStartupFailure(context, LIVE_KEY, "toss.base-url", "https://api.tosspayments.com"));
    }

    @Test
    @DisplayName("test_ 키는 가짜 서버(WireMock 등) 주소를 쓸 수 있다")
    void testKeyAllowsOtherBaseUrl() {
        runWith("test", TEST_KEY)
            .withPropertyValues("toss.base-url=http://127.0.0.1:8089")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @CsvSource({
        "spring.cloud.openfeign.client.config.default.logger-level, HEADERS",
        "spring.cloud.openfeign.client.config.default.logger-level, FULL",
        "spring.cloud.openfeign.client.config.toss-payments.logger-level, HEADERS",
        "spring.cloud.openfeign.client.config.toss-payments.logger-level, full"
    })
    @DisplayName("Feign 로그 수준이 HEADERS · FULL 이면 기동하지 않는다 — Basic 헤더(시크릿 키)가 로그에 남는다")
    void failsWithHeaderLogging(String property, String level) {
        runWith("local", TEST_KEY)
            .withPropertyValues(property + "=" + level)
            .run(context -> assertStartupFailure(context, TEST_KEY, property, "HEADERS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "BASIC"})
    @DisplayName("Feign 로그 수준 NONE · BASIC 은 헤더를 남기지 않아 받는다")
    void acceptsNonHeaderLogging(String level) {
        runWith("local", TEST_KEY)
            .withPropertyValues("spring.cloud.openfeign.client.config.toss-payments.logger-level=" + level)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("결제위젯 live_gsk_ 키도 prod 에서 받는다")
    void acceptsLiveWidgetKeyOnProd() {
        runWith("prod", "live_gsk_abc123XYZ").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("prod 에 test_ 키면 기동하지 않는다 — 결제는 되는데 돈이 안 들어온다")
    void failsWithTestKeyOnProd() {
        runWith("prod", TEST_KEY).run(context -> assertStartupFailure(context, TEST_KEY, "toss.secret-key", "live_"));
    }

    @Test
    @DisplayName("local 에 live_ 키면 기동하지 않는다 — 개발 환경이 실제 결제를 일으킨다")
    void failsWithLiveKeyOnLocal() {
        runWith("local", LIVE_KEY).run(context -> assertStartupFailure(context, LIVE_KEY, "toss.secret-key", "test_"));
    }

    @Test
    @DisplayName("프로파일이 없으면 운영으로 보고 test_ 키를 거절한다 — 프로파일을 빠뜨린 배포가 테스트 키로 뜨지 않는다")
    void failsWithTestKeyWithoutProfile() {
        runner.withPropertyValues("toss.secret-key=" + TEST_KEY)
            .run(context -> assertStartupFailure(context, TEST_KEY, "toss.secret-key", "live_"));
    }

    @Test
    @DisplayName("테스트 프로파일과 다른 프로파일이 같이 켜져 있으면 운영으로 본다")
    void failsWithTestKeyWhenMixedWithOtherProfile() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev", "prod"))
            .withPropertyValues("toss.secret-key=" + TEST_KEY)
            .run(context -> assertStartupFailure(context, TEST_KEY, "toss.secret-key", "live_"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"test_ck_abc123XYZ", "test_gck_abc123XYZ", "live_ck_abc123XYZ", "sk_abc123XYZ", "test_sk_abc-123", "test_sk_abc 123"})
    @DisplayName("시크릿 키 형식이 아니면 기동하지 않는다 — 클라이언트 키(ck · gck)를 넣은 실수도 막는다")
    void failsWithMalformedKey(String key) {
        runWith("local", key).run(context -> assertStartupFailure(context, key, "toss.secret-key", "시크릿 키 형식"));
    }

    @Test
    @DisplayName("키가 없으면 기동하지 않고 TOSS_SECRET_KEY 를 알려준다")
    void failsWithoutKey() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
            .run(context -> assertStartupFailure(context, null, "toss.secret-key", "TOSS_SECRET_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("키가 비었거나 공백뿐이면 기동하지 않는다")
    void failsWithBlankKey(String key) {
        runWith("local", key).run(context -> assertStartupFailure(context, null, "toss.secret-key", "TOSS_SECRET_KEY"));
    }

    @Test
    @DisplayName("환경변수가 없어 ${TOSS_SECRET_KEY} 가 풀리지 않으면 기동하지 않는다 — Binder 는 문자 그대로 남긴다")
    void failsWithUnresolvedPlaceholder() {
        runWith("local", "${TOSS_SECRET_KEY}").run(context -> assertStartupFailure(context, null, "toss.secret-key", "TOSS_SECRET_KEY"));
    }

    @Test
    @DisplayName("Feign 타임아웃이 없으면 기동하지 않는다 — 기본 60초로 돌지 않게")
    void failsWithoutTimeouts() {
        new ApplicationContextRunner()
            .withInitializer(emptySystemEnvironment())
            .withUserConfiguration(GuardConfig.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
            .withPropertyValues(BASE_URL, "toss.secret-key=" + TEST_KEY)
            .run(context -> assertStartupFailure(context, TEST_KEY, "connect-timeout", "read-timeout"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10000", "60000", "6s"})
    @DisplayName("read timeout 이 0 이하 · 게이트웨이 응답 타임아웃(10s) 이상 · 형식 오류면 기동하지 않는다")
    void failsWithInvalidReadTimeout(String readTimeout) {
        runWith("local", TEST_KEY)
            .withPropertyValues("spring.cloud.openfeign.client.config.toss-payments.read-timeout=" + readTimeout)
            .run(context -> assertStartupFailure(context, TEST_KEY, "read-timeout"));
    }

    @Test
    @DisplayName("base-url 플레이스홀더가 풀리지 않으면 기동하지 않는다")
    void failsWithUnresolvedBaseUrl() {
        runWith("local", TEST_KEY)
            .withPropertyValues("toss.base-url=${TOSS_BASE_URL}")
            .run(context -> assertStartupFailure(context, TEST_KEY, "toss.base-url"));
    }

    @Test
    @DisplayName("위반을 모아 한 번에 알린다")
    void reportsAllViolationsAtOnce() {
        new ApplicationContextRunner()
            .withInitializer(emptySystemEnvironment())
            .withUserConfiguration(GuardConfig.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withPropertyValues("toss.secret-key=" + TEST_KEY)
            .run(context -> assertStartupFailure(context, TEST_KEY, "toss.secret-key", "toss.base-url", "connect-timeout", "read-timeout"));
    }

    private ApplicationContextRunner runWith(String profile, String key) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
            .withPropertyValues("toss.secret-key=" + key);
    }

    /** 기동이 실패하고, 메시지가 프로퍼티 이름 · 조건을 담되 키 값은 담지 않는다. */
    private static void assertStartupFailure(AssertableApplicationContext context, String secretValue, String... expectedFragments) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(failure.getMessage()).contains(expectedFragments);
        if (secretValue != null && !secretValue.isBlank()) {
            assertThat(failure.getMessage()).doesNotContain(secretValue);
        }
    }

    /** 개발 PC 의 실제 환경변수(TOSS_SECRET_KEY · SPRING_PROFILES_ACTIVE)가 결과를 바꾸지 않게 비운다 — TOSS_SECRET_KEY 는 toss.secret-key 로 바로 묶인다. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> emptySystemEnvironment() {
        String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        return context -> context.getEnvironment().getPropertySources().replace(name, new SystemEnvironmentPropertySource(name, Map.of()));
    }

    static class GuardConfig {

        @Bean
        static TossPaymentsStartupGuard tossPaymentsStartupGuard(Environment environment) {
            return new TossPaymentsStartupGuard(environment);
        }
    }
}
