package com.commercefoundry.apigateway.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.config.GatewayRateLimitConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 비로그인 IP 계수 스위치의 기본값과 키 검증 시점을 본다 ({@code security-guide.md} §7-3). 기본은 끔이고, 끈 상태에서는 HMAC 키 없이 뜬다 —
 * BFF 뒤에서 켜면 비로그인 요청 전체가 한 버킷이 되기 때문이다 (#134 · #135). 켠 상태의 키 규칙은 {@code ClientIpResolverTest} 가 본다.
 */
class GatewayClientIpPropertiesTest {

    private static final String KEY = GatewayIntegrationTest.TEST_CLIENT_IP_HASH_KEY;

    @Test
    @DisplayName("아무것도 적지 않으면 꺼져 있고 HMAC 키 없이 만들어진다 — 기본 구성의 게이트웨이는 키 없이 뜬다")
    void disabledByDefaultWithoutKey() {
        GatewayClientIpProperties properties = bind(Map.of());

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.trustedProxies()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "${GATEWAY_CLIENT_IP_HASH_KEY}", "not base64!!"})
    @DisplayName("꺼져 있으면 키가 비었거나 풀리지 않았어도 만들어진다")
    void disabledAcceptsMissingKey(String key) {
        assertThat(bind(Map.of("gateway.client-ip.enabled", "false", "gateway.client-ip.hash-key", key)).enabled()).isFalse();
        assertThat(bind(Map.of("gateway.client-ip.hash-key", key)).enabled()).isFalse();
    }

    @Test
    @DisplayName("켰는데 키가 없으면 바인딩에서 실패한다 — 기동하지 않는다")
    void enabledRequiresKey() {
        assertThatThrownBy(() -> bind(Map.of("gateway.client-ip.enabled", "true")))
            .isInstanceOf(BindException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("gateway.client-ip.enabled", "true", "gateway.client-ip.hash-key", "")))
            .isInstanceOf(BindException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("켜고 키를 넣으면 바인딩된다")
    void enabledWithKeyBinds() {
        GatewayClientIpProperties properties = bind(Map.of("gateway.client-ip.enabled", "true", "gateway.client-ip.hash-key", KEY));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.hashKeyBytes()).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    @DisplayName("꺼져 있어도 신뢰 프록시 값은 검사한다 — 잘못된 값이면 기동하지 않는다")
    void disabledStillValidatesTrustedProxies() {
        assertThatThrownBy(() -> new GatewayClientIpProperties(false, List.of("not-a-cidr"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("gateway.client-ip.trusted-proxies", "0.0.0.0/0"))).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("꺼진 상태에서 키를 꺼내면 규칙대로 던진다 — 키를 쓰는 쪽이 꺼진 것을 모르고 부르면 드러난다")
    void disabledKeyBytesStillValidated() {
        GatewayClientIpProperties properties = new GatewayClientIpProperties(false, List.of(), "");

        assertThatThrownBy(properties::hashKeyBytes).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString 에 스위치를 싣고 HMAC 키는 가린다")
    void toStringCarriesEnabledAndMasksKey() {
        assertThat(new GatewayClientIpProperties(true, List.of(), KEY).toString())
            .contains("enabled=true")
            .contains("hashKey=****")
            .doesNotContain(KEY);
    }

    @Test
    @DisplayName("application.yml 의 enabled 는 GATEWAY_CLIENT_IP_ENABLED 로 켜고, 없으면 꺼져 있다 (#135)")
    void enabledBindsFromEnvironmentVariable() {
        assertThat(bindApplicationYml(Map.of()).enabled()).isFalse();
        assertThat(bindApplicationYml(Map.of("GATEWAY_CLIENT_IP_ENABLED", "false")).enabled()).isFalse();
        assertThat(bindApplicationYml(Map.of("GATEWAY_CLIENT_IP_ENABLED", "true", "GATEWAY_CLIENT_IP_HASH_KEY", KEY)).enabled()).isTrue();
    }

    @Test
    @DisplayName("GATEWAY_CLIENT_IP_ENABLED 로 켰는데 키가 없으면 바인딩에서 실패한다")
    void enabledFromEnvironmentRequiresKey() {
        assertThatThrownBy(() -> bindApplicationYml(Map.of("GATEWAY_CLIENT_IP_ENABLED", "true")))
            .isInstanceOf(BindException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"maybe", "ture", "enabled"})
    @DisplayName("GATEWAY_CLIENT_IP_ENABLED 가 불리언이 아니면 바인딩에서 실패한다 — 꺼진 채로 조용히 뜨지 않는다")
    void invalidEnabledValueFailsBinding(String value) {
        assertThatThrownBy(() -> bindApplicationYml(Map.of("GATEWAY_CLIENT_IP_ENABLED", value))).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("GATEWAY_CLIENT_IP_ENABLED= (빈 값)이면 바인딩에서 실패한다 — 기본값(false)으로 떨어지지 않고 기동하지 않는다")
    void emptyEnabledValueFailsBinding() {
        assertThatThrownBy(() -> bindApplicationYml(Map.of("GATEWAY_CLIENT_IP_ENABLED", "")))
            .isInstanceOf(BindException.class)
            .hasMessageContaining("gateway.client-ip.enabled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "::/0"})
    @DisplayName("접두 길이 0 인 신뢰 프록시는 기동을 거부한다 — 누구의 X-Forwarded-For 든 믿게 된다")
    void zeroPrefixTrustedProxyIsRejected(String cidr) {
        assertThatThrownBy(() -> bind(Map.of("gateway.client-ip.trusted-proxies", cidr)))
            .isInstanceOf(BindException.class)
            .rootCause()
            .hasMessageContaining("prefix length out of range");
        assertThatThrownBy(() -> new GatewayClientIpProperties(false, List.of(cidr), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(cidr);
    }

    @Test
    @DisplayName("enabled 가 불리언이 아니면 설정 클래스가 뜨지 않는다 — 기동 실패")
    void invalidEnabledValueFailsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(GatewayRateLimitConfig.class)
            .withPropertyValues("gateway.client-ip.enabled=maybe")
            .run(context -> assertThat(context).hasFailed());
    }

    private static GatewayClientIpProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("gateway.client-ip", GatewayClientIpProperties.class);
    }

    /** 실제 {@code application.yml} 위에 환경변수 자리를 얹어 바인딩한다. 셸 환경변수 · 시스템 프로퍼티는 빼 결과가 셸에 따라 바뀌지 않게 한다. */
    private static GatewayClientIpProperties bindApplicationYml(Map<String, String> environment) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test-environment", new HashMap<String, Object>(environment)));
        try {
            new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Binder.get(env).bindOrCreate("gateway.client-ip", GatewayClientIpProperties.class);
    }
}
