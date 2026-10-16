package com.commercefoundry.security.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 서비스가 {@link InternalResourceServerConfigurer} 를 import 했을 때의 기동 조건을 본다 — 체인 두 개 · {@code JwtDecoder} 빈 하나 · 비밀 가드.
 * 컨텍스트 러너라 테스트 픽스처의 초기화기가 돌지 않는다.
 */
class InternalResourceServerConfigurerTest {

    private static final String PUBLIC_KEY = "security.jwt.public-key=" + TestKeys.publicKeyLine(TestKeys.CURRENT);
    private static final String SERVICE_ID = "security.internal.service-id=product-service";
    private static final String SECRET = "security.internal.secret=" + TestServiceTokens.secretLine(TestServiceTokens.SECRET);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        // 개발 PC 의 실제 JWT_* · INTERNAL_JWT_* 환경변수가 결과를 바꾸지 않게 비운다
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(JwtVerificationPropertiesConfig.class, InternalTokenPropertiesConfig.class);

    @Test
    @DisplayName("서비스처럼 두 Configurer 를 import 하면 체인 두 개 · JwtDecoder 빈 하나 · 보안 advice 하나다")
    void startsWithBothConfigurers() {
        runner.withUserConfiguration(ResourceServerSecurityConfigurer.class, InternalResourceServerConfigurer.class)
            .withPropertyValues(PUBLIC_KEY, SERVICE_ID, SECRET)
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(JwtDecoder.class).hasSingleBean(SecurityExceptionAdvice.class)
                .getBeans(SecurityFilterChain.class).containsOnlyKeys("internalSecurityFilterChain", "resourceServerSecurityFilterChain"));
    }

    @Test
    @DisplayName("internal Configurer 만 import 해도 사용자 체인이 함께 온다 — internal 밖의 경로가 체인 없이 열리지 않는다")
    void internalConfigurerBringsUserChain() {
        runner.withUserConfiguration(InternalResourceServerConfigurer.class)
            .withPropertyValues(PUBLIC_KEY, SERVICE_ID, SECRET)
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(SecurityExceptionAdvice.class)
                .getBeans(SecurityFilterChain.class).containsOnlyKeys("internalSecurityFilterChain", "resourceServerSecurityFilterChain"));
    }

    @Test
    @DisplayName("서비스 토큰 비밀이 없거나 ${INTERNAL_JWT_SECRET} 가 풀리지 않으면 기동하지 않는다")
    void failsWithoutSecret() {
        WebApplicationContextRunner configured = runner.withUserConfiguration(ResourceServerSecurityConfigurer.class, InternalResourceServerConfigurer.class);

        configured.withPropertyValues(PUBLIC_KEY, SERVICE_ID).run(context -> assertStartupFailure(context, "INTERNAL_JWT_SECRET"));
        configured.withPropertyValues(PUBLIC_KEY, SERVICE_ID, "security.internal.secret=${INTERNAL_JWT_SECRET}")
            .run(context -> assertStartupFailure(context, "INTERNAL_JWT_SECRET"));
    }

    private static void assertStartupFailure(AssertableWebApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContainingAll(messageParts);
    }
}
