package com.commercefoundry.security.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 서비스가 {@link ResourceServerSecurityConfigurer} 를 import 했을 때 공개키 설정이 어긋나면 기동하지 않는지 본다.
 */
class ResourceServerSecurityConfigurerTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        // 개발 PC 의 실제 JWT_* 환경변수가 결과를 바꾸지 않게 비운다
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
        .withUserConfiguration(JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class);

    @Test
    @DisplayName("공개키가 있으면 디코더 · 필터 체인 · 보안 advice 를 등록한다")
    void startsWithPublicKey() {
        runner.withPropertyValues("security.jwt.public-key=" + TestKeys.publicKeyLine(TestKeys.CURRENT))
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(JwtDecoder.class).hasSingleBean(SecurityFilterChain.class).hasSingleBean(SecurityExceptionAdvice.class));
    }

    @Test
    @DisplayName("공개키가 없거나 ${JWT_PUBLIC_KEY} 가 풀리지 않으면 기동하지 않는다")
    void failsWithoutPublicKey() {
        runner.run(context -> assertStartupFailure(context, "JWT_PUBLIC_KEY"));
        runner.withPropertyValues("security.jwt.public-key=${JWT_PUBLIC_KEY}").run(context -> assertStartupFailure(context, "JWT_PUBLIC_KEY"));
    }

    @Test
    @DisplayName("이전 공개키 슬롯은 비워 둘 수 있지만, 채웠다면 형식이 맞아야 한다")
    void validatesPreviousKeyOnlyWhenPresent() {
        String publicKey = "security.jwt.public-key=" + TestKeys.publicKeyLine(TestKeys.CURRENT);

        runner.withPropertyValues(publicKey, "security.jwt.previous-public-key=${JWT_PUBLIC_KEY_PREVIOUS:}").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(publicKey, "security.jwt.previous-public-key=" + TestKeys.publicKeyLine(TestKeys.generate(1024)))
            .run(context -> assertStartupFailure(context, "JWT_PUBLIC_KEY_PREVIOUS", "2048"));
    }

    private static void assertStartupFailure(AssertableWebApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContainingAll(messageParts);
    }
}
