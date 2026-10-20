package com.commercefoundry.apigateway.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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

    private static GatewayClientIpProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("gateway.client-ip", GatewayClientIpProperties.class);
    }
}
