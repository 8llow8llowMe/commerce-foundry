package com.commercefoundry.security.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스가 {@link ResourceServerSecurityConfigurer} 를 import 했을 때 공개키 설정이 어긋나면 기동하지 않는지 본다.
 * 서비스처럼 Boot 의 Jackson 자동설정이 {@code JsonMapper} 빈을 만든다.
 */
class ResourceServerSecurityConfigurerTest {

    private static final String PUBLIC_KEY = "security.jwt.public-key=" + TestKeys.publicKeyLine(TestKeys.CURRENT);

    private final WebApplicationContextRunner withoutJackson = new WebApplicationContextRunner()
        // 개발 PC 의 실제 JWT_* 환경변수가 결과를 바꾸지 않게 비운다
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
        .withUserConfiguration(JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class);

    private final WebApplicationContextRunner runner = withoutJackson.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    @DisplayName("공개키가 있으면 디코더 · 필터 체인 · 보안 advice 를 등록한다")
    void startsWithPublicKey() {
        runner.withPropertyValues(PUBLIC_KEY)
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(JwtDecoder.class).hasSingleBean(SecurityFilterChain.class).hasSingleBean(SecurityExceptionAdvice.class));
    }

    @Test
    @DisplayName("JsonMapper 빈이 없으면 기본 인스턴스로 바꾸지 않고 기동하지 않는다 — 오류 봉투가 서비스 직렬화 설정과 갈라지지 않게")
    void failsWithoutJsonMapperBean() {
        withoutJackson.withPropertyValues(PUBLIC_KEY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOf(NoSuchBeanDefinitionException.class).hasMessageContaining(JsonMapper.class.getName());
        });
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
        runner.withPropertyValues(PUBLIC_KEY, "security.jwt.previous-public-key=${JWT_PUBLIC_KEY_PREVIOUS:}").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(PUBLIC_KEY, "security.jwt.previous-public-key=" + TestKeys.publicKeyLine(TestKeys.generate(1024)))
            .run(context -> assertStartupFailure(context, "JWT_PUBLIC_KEY_PREVIOUS", "2048"));
    }

    private static void assertStartupFailure(AssertableWebApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContainingAll(messageParts);
    }
}
