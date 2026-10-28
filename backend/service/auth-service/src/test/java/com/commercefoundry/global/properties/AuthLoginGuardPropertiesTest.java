package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.support.TestLoginGuard;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 시도 제한 프로퍼티의 기본값 · 범위 · 키 검증 시점을 본다 (#66). 레코드 기본값은 켬(닫힌 기본값)이라 키가 있어야 뜨고, 끈 상태에서는 HMAC 키 없이 뜬다.
 * 여기서는 yml 없이 바인딩한다 — application.yml 도 켠다(#66 A2).
 */
class AuthLoginGuardPropertiesTest {

    private static final String KEY = TestLoginGuard.HASH_KEY;
    private static final String HASH_KEY = "auth.login-guard.hash-key";

    @Test
    @DisplayName("키만 넣으면 기본값 — 켬 · 로그인 5회 · 15분 · 가입 5회 · 10분")
    void defaults() {
        AuthLoginGuardProperties properties = bind(Map.of(HASH_KEY, KEY));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(properties.window()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.signUp()).isEqualTo(new AuthLoginGuardProperties.SignUp(5, Duration.ofMinutes(10)));
        assertThat(properties.hashKeyBytes()).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    @DisplayName("가입 창은 sign-up.* 로 따로 바인딩하고, 직접 만들 때 생략하면 기본값이다")
    void signUpBindsSeparately() {
        AuthLoginGuardProperties properties = bind(Map.of(HASH_KEY, KEY, "auth.login-guard.sign-up.max-attempts", "3", "auth.login-guard.sign-up.window", "30m"));

        assertThat(properties.signUp()).isEqualTo(new AuthLoginGuardProperties.SignUp(3, Duration.ofMinutes(30)));
        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(TestLoginGuard.properties().signUp()).isEqualTo(new AuthLoginGuardProperties.SignUp(5, Duration.ofMinutes(10)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sign-up.max-attempts=0", "sign-up.max-attempts=101", "sign-up.window=59s", "sign-up.window=61m"})
    @DisplayName("가입 창의 횟수 · 길이도 로그인과 같은 범위다 — 벗어나면 바인딩에서 실패하고 메시지가 sign-up 을 가리킨다")
    void signUpRangeRejected(String entry) {
        String[] pair = entry.split("=");
        assertThatThrownBy(() -> bind(Map.of(HASH_KEY, KEY, "auth.login-guard." + pair[0], pair[1])))
            .isInstanceOf(BindException.class)
            .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.login-guard.sign-up.");
    }

    @Test
    @DisplayName("아무것도 적지 않거나 켜기만 하면 키가 없어 바인딩에서 실패한다 — 기동하지 않는다")
    void enabledRequiresKey() {
        assertThatThrownBy(() -> bind(Map.of()))
            .isInstanceOf(BindException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("auth.login-guard.enabled", "true")))
            .isInstanceOf(BindException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "5", "100"})
    @DisplayName("허용 횟수는 1 이상 100 이하면 받는다")
    void maxAttemptsAccepted(String maxAttempts) {
        assertThat(bind(Map.of(HASH_KEY, KEY, "auth.login-guard.max-attempts", maxAttempts)).maxAttempts()).isEqualTo(Integer.parseInt(maxAttempts));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101"})
    @DisplayName("허용 횟수가 범위 밖이면 바인딩에서 실패한다 — 꺼져 있어도")
    void maxAttemptsRejected(String maxAttempts) {
        assertThatThrownBy(() -> bind(Map.of(HASH_KEY, KEY, "auth.login-guard.max-attempts", maxAttempts)))
            .isInstanceOf(BindException.class)
            .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.login-guard.max-attempts");
        assertThatThrownBy(() -> bind(Map.of("auth.login-guard.enabled", "false", "auth.login-guard.max-attempts", maxAttempts)))
            .isInstanceOf(BindException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1m", "15m", "1h", "60m"})
    @DisplayName("창은 1분 이상 1시간 이하면 받는다")
    void windowAccepted(String window) {
        assertThat(bind(Map.of(HASH_KEY, KEY, "auth.login-guard.window", window)).window()).isBetween(Duration.ofMinutes(1), Duration.ofHours(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"59s", "0s", "-1m", "61m", "3601s", "24h"})
    @DisplayName("창이 범위 밖이면 바인딩에서 실패한다 — 꺼져 있어도")
    void windowRejected(String window) {
        assertThatThrownBy(() -> bind(Map.of(HASH_KEY, KEY, "auth.login-guard.window", window)))
            .isInstanceOf(BindException.class)
            .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.login-guard.window");
        assertThatThrownBy(() -> bind(Map.of("auth.login-guard.enabled", "false", "auth.login-guard.window", window)))
            .isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("창이 null 이면(직접 생성) 실패한다")
    void nullWindowRejected() {
        assertThatThrownBy(() -> new AuthLoginGuardProperties(true, 5, null, KEY, null)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "${AUTH_LOGIN_GUARD_HASH_KEY}", "not base64!!"})
    @DisplayName("켰는데 키가 비었거나 · 공백이거나 · 풀리지 않았거나 · base64 가 아니면 바인딩에서 실패하고, 메시지에 값을 싣지 않는다")
    void enabledRejectsInvalidKey(String key) {
        assertThatThrownBy(() -> bind(Map.of("auth.login-guard.enabled", "true", HASH_KEY, key)))
            .isInstanceOf(BindException.class)
            .rootCause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining("auth.login-guard.hash-key");
        assertThatThrownBy(() -> new AuthLoginGuardProperties(true, 5, Duration.ofMinutes(15), key, null)).isInstanceOf(IllegalArgumentException.class);
        if (!key.isBlank()) {
            assertThatThrownBy(() -> new AuthLoginGuardProperties(true, 5, Duration.ofMinutes(15), key, null)).hasMessageNotContaining(key);
        }
    }

    @Test
    @DisplayName("켰는데 키가 32바이트 미만이면 실패한다 — 메시지에 값을 싣지 않는다")
    void enabledRejectsShortKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> bind(Map.of(HASH_KEY, shortKey)))
            .isInstanceOf(BindException.class)
            .rootCause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32바이트").hasMessageNotContaining(shortKey);
    }

    @Test
    @DisplayName("키가 32바이트면 받는다 — 앞뒤 공백 · 줄바꿈은 지운다")
    void acceptsExactly32BytesWithSurroundingWhitespace() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThat(bind(Map.of(HASH_KEY, " " + key + "\n")).hashKeyBytes()).hasSize(32);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "${AUTH_LOGIN_GUARD_HASH_KEY}", "not base64!!"})
    @DisplayName("끄면 키가 없거나 규칙 밖이어도 만들어진다")
    void disabledAcceptsMissingKey(String key) {
        assertThat(bind(Map.of("auth.login-guard.enabled", "false")).enabled()).isFalse();
        assertThat(bind(Map.of("auth.login-guard.enabled", "false", HASH_KEY, key)).enabled()).isFalse();
    }

    @Test
    @DisplayName("꺼진 상태에서 키를 꺼내면 규칙대로 던진다 — 키를 쓰는 쪽이 꺼진 것을 모르고 부르면 드러난다")
    void disabledKeyBytesStillValidated() {
        AuthLoginGuardProperties properties = new AuthLoginGuardProperties(false, 5, Duration.ofMinutes(15), null, null);

        assertThatThrownBy(properties::hashKeyBytes).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString 에 스위치 · 횟수 · 창을 싣고 HMAC 키는 가린다")
    void toStringMasksKey() {
        assertThat(TestLoginGuard.properties().toString())
            .contains("enabled=true", "maxAttempts=5", "window=PT15M", "hashKey=****")
            .doesNotContain(KEY);
    }

    private static AuthLoginGuardProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("auth.login-guard", AuthLoginGuardProperties.class);
    }
}
