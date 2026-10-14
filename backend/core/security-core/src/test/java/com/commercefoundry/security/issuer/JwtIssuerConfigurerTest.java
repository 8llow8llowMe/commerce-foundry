package com.commercefoundry.security.issuer;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.token.AccessTokenDecoderFactory;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * auth-service 가 {@link JwtIssuerConfigurer} 를 import 했을 때의 기동 조건을 본다.
 */
class JwtIssuerConfigurerTest {

    private static final String PUBLIC_KEY = "security.jwt.public-key=" + TestKeys.publicKeyLine(TestKeys.CURRENT);
    private static final String PRIVATE_KEY = "security.jwt.issuance.private-key=" + TestKeys.privateKeyLine(TestKeys.CURRENT);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        // 개발 PC 의 실제 JWT_* 환경변수가 결과를 바꾸지 않게 비운다
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
        .withUserConfiguration(JwtVerificationPropertiesConfig.class, JwtIssuerPropertiesConfig.class, JwtIssuerConfigurer.class);

    @Test
    @DisplayName("개인키와 공개키가 한 쌍이면 발급기와 불투명 토큰 생성기를 등록하고, 발급한 토큰이 같은 설정의 검증기를 통과한다")
    void startsWithMatchingKeyPair() {
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtIssuer.class).hasSingleBean(OpaqueTokenGenerator.class);

            IssuedAccessToken issued = context.getBean(JwtIssuer.class).issueAccessToken(7L, MemberRole.ADMIN);
            Jwt jwt = AccessTokenDecoderFactory.create(context.getBean(JwtVerificationProperties.class), Clock.systemUTC()).decode(issued.tokenValue());

            assertThat(jwt.getSubject()).isEqualTo("7");
            assertThat(jwt.getExpiresAt()).isEqualTo(issued.expiresAt());
        });
    }

    @Test
    @DisplayName("기본값 — iss commerce-foundry-auth · aud commerce-foundry-api · 시계 오차 60초 · access 30분 · refresh 14일")
    void bindsDefaults() {
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY).run(context -> {
            JwtVerificationProperties verification = context.getBean(JwtVerificationProperties.class);
            JwtIssuanceProperties issuance = context.getBean(JwtIssuanceProperties.class);

            assertThat(verification.issuer()).isEqualTo("commerce-foundry-auth");
            assertThat(verification.audience()).isEqualTo("commerce-foundry-api");
            assertThat(verification.clockSkew()).isEqualTo(Duration.ofSeconds(60));
            assertThat(verification.previousPublicKey()).isNull();
            assertThat(issuance.accessTokenTtl()).isEqualTo(Duration.ofMinutes(30));
            assertThat(issuance.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
            assertThat(issuance.toString()).doesNotContain(TestKeys.privateKeyLine(TestKeys.CURRENT).substring(40, 80));
        });
    }

    @Test
    @DisplayName("개인키가 공개키와 한 쌍이 아니면 기동하지 않는다 — 두 환경변수 이름을 알리고 키 내용은 싣지 않는다")
    void failsWhenKeysDoNotMatch() {
        runner.withPropertyValues(PUBLIC_KEY, "security.jwt.issuance.private-key=" + TestKeys.privateKeyLine(TestKeys.OTHER))
            .run(context -> {
                assertStartupFailure(context, "JWT_PRIVATE_KEY", "JWT_PUBLIC_KEY", "한 쌍이 아닙니다");
                assertNoKeyMaterial(context, TestKeys.privateKeyLine(TestKeys.OTHER));
            });
    }

    @Test
    @DisplayName("개인키가 없거나 ${JWT_PRIVATE_KEY} 가 풀리지 않으면 기동하지 않는다")
    void failsWithoutPrivateKey() {
        runner.withPropertyValues(PUBLIC_KEY).run(context -> assertStartupFailure(context, "JWT_PRIVATE_KEY"));
        runner.withPropertyValues(PUBLIC_KEY, "security.jwt.issuance.private-key=${JWT_PRIVATE_KEY}")
            .run(context -> assertStartupFailure(context, "JWT_PRIVATE_KEY"));
    }

    @Test
    @DisplayName("access · refresh 수명이 0 이하면 기동하지 않는다")
    void failsWithNonPositiveTtl() {
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.issuance.access-token-ttl=0s")
            .run(context -> assertStartupFailure(context, "security.jwt.issuance.access-token-ttl"));
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.issuance.refresh-token-ttl=-1d")
            .run(context -> assertStartupFailure(context, "security.jwt.issuance.refresh-token-ttl"));
    }

    @Test
    @DisplayName("access 수명은 1시간까지다 — 넘으면 프로퍼티 이름과 이유(탈취 창 · 블랙리스트)를 알리고 기동하지 않는다")
    void failsWithAccessTokenTtlOverOneHour() {
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.issuance.access-token-ttl=1h")
            .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.issuance.access-token-ttl=61m")
            .run(context -> assertStartupFailure(context, "security.jwt.issuance.access-token-ttl", "1시간", "블랙리스트"));
    }

    @Test
    @DisplayName("시계 오차 허용이 음수거나 5분을 넘으면, iss · aud 가 비면 기동하지 않는다")
    void failsWithInvalidVerificationSettings() {
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.clock-skew=6m")
            .run(context -> assertStartupFailure(context, "security.jwt.clock-skew"));
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.clock-skew=-1s")
            .run(context -> assertStartupFailure(context, "security.jwt.clock-skew"));
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.issuer= ")
            .run(context -> assertStartupFailure(context, "security.jwt.issuer"));
        runner.withPropertyValues(PUBLIC_KEY, PRIVATE_KEY, "security.jwt.audience=")
            .run(context -> assertStartupFailure(context, "security.jwt.audience"));
    }

    private static void assertStartupFailure(AssertableApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContainingAll(messageParts);
    }

    private static void assertNoKeyMaterial(AssertableApplicationContext context, String keyLine) {
        for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain(keyLine.substring(40, 80));
        }
    }
}
