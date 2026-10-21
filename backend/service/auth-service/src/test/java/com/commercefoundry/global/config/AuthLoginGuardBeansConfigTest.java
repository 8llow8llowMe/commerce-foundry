package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import com.commercefoundry.support.TestLoginGuard;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 시도 제한 정책 빈 — {@code auth.login-guard.*} 바인딩에서 정책 값 묶음이 만들어지는지 본다. 범위 · 키 규칙은 {@code AuthLoginGuardPropertiesTest} 가 본다.
 */
class AuthLoginGuardBeansConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthLoginGuardProperties.class)
    static class AuthLoginGuardPropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AuthLoginGuardPropertiesOnly.class, AuthLoginGuardBeansConfig.class)
        .withPropertyValues("auth.login-guard.hash-key=" + TestLoginGuard.HASH_KEY);

    @Test
    @DisplayName("기본값 — 5회 · 15분")
    void defaults() {
        runner.run(context -> assertThat(context).hasNotFailed().getBean(LoginAttemptPolicy.class)
            .isEqualTo(new LoginAttemptPolicy(5, Duration.ofMinutes(15))));
    }

    @Test
    @DisplayName("프로퍼티 값이 그대로 정책이 된다")
    void bindsConfiguredValues() {
        runner.withPropertyValues("auth.login-guard.max-attempts=10", "auth.login-guard.window=5m")
            .run(context -> assertThat(context.getBean(LoginAttemptPolicy.class)).isEqualTo(new LoginAttemptPolicy(10, Duration.ofMinutes(5))));
    }

    @Test
    @DisplayName("꺼져 있으면 키 없이 뜨고 정책 빈도 만들어진다")
    void disabledStartsWithoutKey() {
        new ApplicationContextRunner()
            .withUserConfiguration(AuthLoginGuardPropertiesOnly.class, AuthLoginGuardBeansConfig.class)
            .withPropertyValues("auth.login-guard.enabled=false")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LoginAttemptPolicy.class));
    }

    @Test
    @DisplayName("켜져 있는데 키가 없으면 기동하지 않는다")
    void enabledWithoutKeyFailsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(AuthLoginGuardPropertiesOnly.class, AuthLoginGuardBeansConfig.class)
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.login-guard.hash-key"));
    }
}
