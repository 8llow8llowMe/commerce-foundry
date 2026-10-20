package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.global.properties.AuthTokenProperties;
import com.commercefoundry.security.issuer.JwtIssuanceProperties;
import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.support.TestTokens;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 회전 정책 빈 — {@code auth.token.*} 바인딩과 refresh TTL(발급 설정, 테스트 픽스처 14일)을 함께 보는 기동 검사.
 * 발급 설정은 키까지 요구해 바인딩 대신 테스트 픽스처 빈으로 넣는다.
 */
class AuthTokenBeansConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthTokenProperties.class)
    static class AuthTokenPropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AuthTokenPropertiesOnly.class, AuthTokenBeansConfig.class)
        .withBean(JwtIssuanceProperties.class, () -> TestTokens.issuance(TestKeys.CURRENT));

    @Test
    @DisplayName("기본값 — 유예 30초 · 형제 3 · refresh TTL 14일 · family 절대 수명 30일")
    void defaults() {
        runner.run(context -> assertThat(context).hasNotFailed().getBean(RefreshTokenRotationPolicy.class)
            .isEqualTo(new RefreshTokenRotationPolicy(Duration.ofSeconds(30), 3, Duration.ofDays(14), Duration.ofDays(30))));
    }

    @Test
    @DisplayName("절대 수명이 refresh TTL 과 같으면 받는다")
    void equalToRefreshTtl() {
        runner.withPropertyValues("auth.token.refresh-family-max-lifetime=14d")
            .run(context -> assertThat(context.getBean(RefreshTokenRotationPolicy.class).familyMaxLifetime()).isEqualTo(Duration.ofDays(14)));
    }

    @Test
    @DisplayName("절대 수명이 refresh TTL(14일)보다 짧으면 기동하지 않는다")
    void shorterThanRefreshTtlFailsStartup() {
        runner.withPropertyValues("auth.token.refresh-family-max-lifetime=13d")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.token.refresh-family-max-lifetime")
                .hasMessageContaining("security.jwt.issuance.refresh-token-ttl"));
    }

    @Test
    @DisplayName("절대 수명이 상한 90일과 같으면 받는다")
    void equalToUpperBound() {
        runner.withPropertyValues("auth.token.refresh-family-max-lifetime=90d")
            .run(context -> assertThat(context.getBean(RefreshTokenRotationPolicy.class).familyMaxLifetime()).isEqualTo(Duration.ofDays(90)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1d", "91d", "P90DT0.001S", "1000000000000d"})
    @DisplayName("절대 수명이 0 이하 · 90일 초과(밀리초로 바꾸면 long 을 넘는 1조 일 포함)면 기동하지 않는다")
    void outOfRangeFailsStartup(String maxLifetime) {
        runner.withPropertyValues("auth.token.refresh-family-max-lifetime=" + maxLifetime)
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.token.refresh-family-max-lifetime"));
    }
}
