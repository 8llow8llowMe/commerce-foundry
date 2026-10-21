package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import com.commercefoundry.domainlayer.member.domain.model.LoginGuardPolicy;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import com.commercefoundry.support.TestLoginGuard;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

/**
 * 시도 제한 정책 빈 — {@code auth.login-guard.*} 바인딩에서 정책 값 묶음이 만들어지는지 본다. 범위 · 키 규칙은 {@code AuthLoginGuardPropertiesTest} 가 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthLoginGuardBeansConfigTest {

    private static final String DISABLED_WARNING = "auth.login-guard.enabled=false";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthLoginGuardProperties.class)
    static class AuthLoginGuardPropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AuthLoginGuardPropertiesOnly.class, AuthLoginGuardBeansConfig.class)
        .withPropertyValues("auth.login-guard.hash-key=" + TestLoginGuard.HASH_KEY);

    @Test
    @DisplayName("기본값 — 켬 · 로그인(고객 · 관리자) 5회 · 15분 · 가입 5회 · 10분. 켜져 있으면 꺼짐 경고를 남기지 않는다")
    void defaults(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LoginGuardPolicy policy = context.getBean(LoginGuardPolicy.class);
            assertThat(policy.enabled()).isTrue();
            assertThat(policy.of(AttemptKind.LOGIN)).isEqualTo(new LoginAttemptPolicy(5, Duration.ofMinutes(15)));
            assertThat(policy.of(AttemptKind.ADMIN_LOGIN)).isEqualTo(new LoginAttemptPolicy(5, Duration.ofMinutes(15)));
            assertThat(policy.of(AttemptKind.SIGN_UP)).isEqualTo(new LoginAttemptPolicy(5, Duration.ofMinutes(10)));
        });
        assertThat(output.getOut()).doesNotContain(DISABLED_WARNING);
    }

    @Test
    @DisplayName("프로퍼티 값이 그대로 정책이 된다 — 로그인 · 가입을 따로 정한다")
    void bindsConfiguredValues() {
        runner.withPropertyValues("auth.login-guard.max-attempts=10", "auth.login-guard.window=5m",
                "auth.login-guard.sign-up.max-attempts=3", "auth.login-guard.sign-up.window=20m")
            .run(context -> {
                LoginGuardPolicy policy = context.getBean(LoginGuardPolicy.class);
                assertThat(policy.login()).isEqualTo(new LoginAttemptPolicy(10, Duration.ofMinutes(5)));
                assertThat(policy.signUp()).isEqualTo(new LoginAttemptPolicy(3, Duration.ofMinutes(20)));
            });
    }

    @Test
    @DisplayName("꺼져 있으면 키 없이 뜨고 정책 빈은 꺼짐이며, 기동 로그를 한 번 남긴다")
    void disabledStartsWithoutKey(CapturedOutput output) {
        new ApplicationContextRunner()
            .withUserConfiguration(AuthLoginGuardPropertiesOnly.class, AuthLoginGuardBeansConfig.class)
            .withPropertyValues("auth.login-guard.enabled=false")
            .run(context -> assertThat(context).hasNotFailed().getBean(LoginGuardPolicy.class).extracting(LoginGuardPolicy::enabled).isEqualTo(false));
        assertThat(output.getOut().split(DISABLED_WARNING, -1)).hasSize(2);
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
