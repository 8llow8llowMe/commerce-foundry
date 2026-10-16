package com.commercefoundry.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.support.TestServiceTokens;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 서비스가 {@link InternalTokenPropertiesConfig} 를 import 했을 때의 기동 가드를 본다. 컨텍스트 러너라 테스트 픽스처의 초기화기가 돌지 않는다.
 */
class InternalTokenPropertiesTest {

    private static final String SERVICE_ID = "security.internal.service-id=order-service";
    private static final String SECRET = "security.internal.secret=" + TestServiceTokens.secretLine(TestServiceTokens.SECRET);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        // 개발 PC 의 실제 INTERNAL_JWT_* 환경변수가 결과를 바꾸지 않게 비운다
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
        .withUserConfiguration(InternalTokenPropertiesConfig.class);

    @Test
    @DisplayName("기본값 — 수명 60초, 이전 비밀 없음. toString 은 비밀을 가린다")
    void bindsDefaults() {
        runner.withPropertyValues(SERVICE_ID, SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            InternalTokenProperties properties = context.getBean(InternalTokenProperties.class);

            assertThat(properties.serviceId()).isEqualTo("order-service");
            assertThat(properties.tokenTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.previousKey()).isEmpty();
            assertThat(properties.currentKey().getEncoded()).isEqualTo(TestServiceTokens.SECRET);
            assertThat(properties.toString()).contains("order-service", "****").doesNotContain(TestServiceTokens.secretLine(TestServiceTokens.SECRET));
        });
    }

    @Test
    @DisplayName("service-id 는 spring.application.name 에서 풀린다 — 서비스 yml 의 ${spring.application.name}")
    void resolvesServiceIdFromApplicationName() {
        runner.withPropertyValues("spring.application.name=product-service", "security.internal.service-id=${spring.application.name}", SECRET)
            .run(context -> assertThat(context.getBean(InternalTokenProperties.class).serviceId()).isEqualTo("product-service"));
    }

    @Test
    @DisplayName("비밀이 없거나 ${INTERNAL_JWT_SECRET} 가 풀리지 않으면 기동하지 않는다 — 프로퍼티 · 환경변수 이름을 알린다")
    void failsWithoutSecret() {
        runner.withPropertyValues(SERVICE_ID).run(context -> assertStartupFailure(context, "security.internal.secret", "INTERNAL_JWT_SECRET"));
        runner.withPropertyValues(SERVICE_ID, "security.internal.secret=${INTERNAL_JWT_SECRET}")
            .run(context -> assertStartupFailure(context, "INTERNAL_JWT_SECRET", "플레이스홀더"));
        runner.withPropertyValues(SERVICE_ID, "security.internal.secret=  ").run(context -> assertStartupFailure(context, "INTERNAL_JWT_SECRET"));
    }

    @Test
    @DisplayName("비밀이 base64 가 아니면 기동하지 않는다 — 메시지에 값이 없다")
    void failsWhenSecretIsNotBase64() {
        String notBase64 = "not*base64*" + "x".repeat(40);

        runner.withPropertyValues(SERVICE_ID, "security.internal.secret=" + notBase64).run(context -> {
            assertStartupFailure(context, "INTERNAL_JWT_SECRET", "base64");
            assertNoSecretInFailure(context, notBase64);
        });
    }

    @Test
    @DisplayName("디코드한 비밀이 32바이트 미만이면 기동하지 않는다 — 31바이트는 거부, 32바이트는 통과")
    void failsWhenSecretIsShorterThan32Bytes() {
        String shortSecret = Base64.getEncoder().encodeToString(TestServiceTokens.random(31));

        runner.withPropertyValues(SERVICE_ID, "security.internal.secret=" + shortSecret).run(context -> {
            assertStartupFailure(context, "INTERNAL_JWT_SECRET", "31바이트", "32바이트 이상");
            assertNoSecretInFailure(context, shortSecret);
        });
        runner.withPropertyValues(SERVICE_ID, "security.internal.secret=" + Base64.getEncoder().encodeToString(TestServiceTokens.random(32)))
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("패딩 없는 base64 와 앞뒤 공백도 읽는다 — openssl 출력을 옮기다 생기는 차이")
    void acceptsUnpaddedAndSurroundingWhitespace() {
        String unpadded = Base64.getEncoder().withoutPadding().encodeToString(TestServiceTokens.SECRET);

        runner.withPropertyValues(SERVICE_ID, "security.internal.secret= " + unpadded + " ")
            .run(context -> assertThat(context.getBean(InternalTokenProperties.class).currentKey().getEncoded()).isEqualTo(TestServiceTokens.SECRET));
    }

    @Test
    @DisplayName("이전 비밀은 비워 둘 수 있지만, 채웠다면 같은 규칙이다 — 메시지는 INTERNAL_JWT_SECRET_PREVIOUS 를 가리킨다")
    void validatesPreviousSecretOnlyWhenPresent() {
        runner.withPropertyValues(SERVICE_ID, SECRET, "security.internal.previous-secret=${INTERNAL_JWT_SECRET_PREVIOUS:}")
            .run(context -> assertThat(context.getBean(InternalTokenProperties.class).previousKey()).isEmpty());
        runner.withPropertyValues(SERVICE_ID, SECRET, "security.internal.previous-secret=" + TestServiceTokens.secretLine(TestServiceTokens.PREVIOUS_SECRET))
            .run(context -> assertThat(context.getBean(InternalTokenProperties.class).previousKey()).get()
                .extracting(key -> key.getEncoded()).isEqualTo(TestServiceTokens.PREVIOUS_SECRET));
        String shortPrevious = Base64.getEncoder().encodeToString(TestServiceTokens.random(16));
        runner.withPropertyValues(SERVICE_ID, SECRET, "security.internal.previous-secret=" + shortPrevious).run(context -> {
            assertStartupFailure(context, "INTERNAL_JWT_SECRET_PREVIOUS", "16바이트");
            // 이전 슬롯이 틀려도 현재 비밀(정상 값)이 진단 출력으로 새지 않는다
            assertNoSecretInFailure(context, TestServiceTokens.secretLine(TestServiceTokens.SECRET));
            assertNoSecretInFailure(context, shortPrevious);
        });
    }

    @Test
    @DisplayName("수명은 0 보다 크고 5분 이하다")
    void validatesTokenTtl() {
        runner.withPropertyValues(SERVICE_ID, SECRET, "security.internal.token-ttl=5m").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(SERVICE_ID, SECRET, "security.internal.token-ttl=301s")
            .run(context -> assertStartupFailure(context, "security.internal.token-ttl", "5분"));
        runner.withPropertyValues(SERVICE_ID, SECRET, "security.internal.token-ttl=0s")
            .run(context -> assertStartupFailure(context, "security.internal.token-ttl"));
        runner.withPropertyValues(SERVICE_ID, SECRET, "security.internal.token-ttl=-1s")
            .run(context -> assertStartupFailure(context, "security.internal.token-ttl"));
    }

    @Test
    @DisplayName("service-id 가 없거나 ${spring.application.name} 이 풀리지 않으면 기동하지 않는다")
    void failsWithoutServiceId() {
        runner.withPropertyValues(SECRET).run(context -> assertStartupFailure(context, "security.internal.service-id"));
        runner.withPropertyValues(SECRET, "security.internal.service-id=${spring.application.name}")
            .run(context -> assertStartupFailure(context, "security.internal.service-id", "spring.application.name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Order-Service", "order_service", "-order", "order service", "ORDER-SERVICE"})
    @DisplayName("service-id 는 소문자 kebab 이어야 한다 — aud · 권한 이름이 되므로 Eureka 서비스 ID 와 같은 모양만 받는다")
    void failsWithMalformedServiceId(String serviceId) {
        runner.withPropertyValues(SECRET, "security.internal.service-id=" + serviceId)
            .run(context -> assertStartupFailure(context, "security.internal.service-id", "소문자 kebab"));
    }

    private static void assertStartupFailure(AssertableApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContainingAll(messageParts);
    }

    /**
     * 메시지뿐 아니라 바인딩 실패가 들고 있는 속성 값도 본다 — Boot 의 기동 실패 진단(BindFailureAnalyzer)이 {@code Property · Value} 로 그대로 찍는다.
     */
    private static void assertNoSecretInFailure(AssertableApplicationContext context, String secret) {
        for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain(secret);
            if (cause instanceof BindException bind && bind.getProperty() != null) {
                assertThat(String.valueOf(bind.getProperty().getValue())).as("BindException 의 속성 값 — " + bind.getProperty().getName()).doesNotContain(secret);
            }
        }
    }
}
