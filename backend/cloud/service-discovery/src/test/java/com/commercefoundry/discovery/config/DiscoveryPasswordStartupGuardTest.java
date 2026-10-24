package com.commercefoundry.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class DiscoveryPasswordStartupGuardTest {

    private static final String HEX_64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("openssl rand -hex 32 형식이면 기동한다")
    void acceptsHex() {
        assertThatCode(() -> guard(HEX_64)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비밀번호가 없으면 기동하지 않는다 — 인증을 끄는 기본값은 없다")
    void rejectsMissing() {
        assertThatThrownBy(() -> new DiscoveryPasswordStartupGuard(new MockEnvironment()).postProcessBeanFactory(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EUREKA_PASSWORD");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        " ",
        "${EUREKA_PASSWORD}",
        "0123456789abcdef0123456789abcde",  // 31자
        "0123456789abcdef:0123456789abcdef0123456789abcdef",  // URL userinfo 구분자
        "0123456789abcdef@0123456789abcdef0123456789abcdef",
        "0123456789abcdef%3A0123456789abcdef0123456789abcdef",
        "0123456789abcdef 0123456789abcdef0123456789abcdef"
    })
    @DisplayName("비었거나 · 풀리지 않았거나 · 짧거나 · URL 에 그대로 실을 수 없는 문자가 있으면 기동하지 않고, 메시지에 값을 싣지 않는다")
    void rejectsInvalid(String password) {
        assertThatThrownBy(() -> guard(password))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EUREKA_PASSWORD")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(password.strip().isEmpty() ? "\0" : password));
    }

    private static void guard(String password) {
        new DiscoveryPasswordStartupGuard(new MockEnvironment().withProperty(DiscoveryPasswordStartupGuard.PASSWORD, password))
            .postProcessBeanFactory(null);
    }
}
