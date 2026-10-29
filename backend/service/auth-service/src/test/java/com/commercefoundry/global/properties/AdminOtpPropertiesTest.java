package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** yml 없이 바인딩해 기본값 · 키 규칙 · TTL 범위를 본다 (#238). 값은 전부 테스트 전용이다. 비밀이 아니다. */
class AdminOtpPropertiesTest {

    /** base64("auth-admin-otp-test-only-key-32b") — 32바이트. */
    private static final String KEY = "YXV0aC1hZG1pbi1vdHAtdGVzdC1vbmx5LWtleS0zMmI=";
    /** base64("auth-admin-otp-test-only-prev-32") — 32바이트. */
    private static final String OTHER_KEY = "YXV0aC1hZG1pbi1vdHAtdGVzdC1vbmx5LXByZXYtMzI=";
    /** 32바이트 0x01 — test-only 를 품지 않는 키(거절 규칙의 반대 경우). 테스트 전용이다. */
    private static final String NON_TEST_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    /** base64("auth-admin-otp-test-only-short") — 30바이트. */
    private static final String SHORT_KEY = "YXV0aC1hZG1pbi1vdHAtdGVzdC1vbmx5LXNob3J0";
    /** base64("auth-admin-otp-test-only-key-of-33") — 34바이트. AES-256 키는 정확히 32바이트다. */
    private static final String LONG_KEY = "YXV0aC1hZG1pbi1vdHAtdGVzdC1vbmx5LWtleS1vZi0zMw==";
    private static final String SECRET_KEY = "auth.admin-otp.secret-key";
    private static final String PREVIOUS = "auth.admin-otp.previous-secret-key";
    private static final String ALLOW_TEST_ONLY_KEY = "auth.admin-otp.allow-test-only-key";

    @Test
    @DisplayName("키만 넣으면 기본값 — 발급자 'Commerce Foundry Admin' · 등록 15분 · 챌린지 5분 · 이전 키 없음")
    void defaults() {
        AdminOtpProperties properties = bind(Map.of(SECRET_KEY, KEY));

        assertThat(properties.issuer()).isEqualTo("Commerce Foundry Admin");
        assertThat(properties.enrollmentTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.challengeTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.secretKeyBytes()).hasSize(32);
        assertThat(properties.previousSecretKeyBytes()).isEmpty();
    }

    @Test
    @DisplayName("이전 키를 채우면 복호화용으로 읽는다. 빈 값은 없음이다")
    void previousKey() {
        assertThat(bind(Map.of(SECRET_KEY, KEY, PREVIOUS, OTHER_KEY)).previousSecretKeyBytes()).hasValueSatisfying(bytes -> assertThat(bytes).hasSize(32));
        assertThat(bind(Map.of(SECRET_KEY, KEY, PREVIOUS, "")).previousSecretKeyBytes()).isEmpty();
    }

    @Test
    @DisplayName("키가 없으면 기동하지 않는다 — OTP 를 쓰는 관리자가 없어도 필수다")
    void keyRequired() {
        assertBindFails(Map.of(), "AUTH_OTP_SECRET_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "${AUTH_OTP_SECRET_KEY}", "not base64!!", SHORT_KEY, LONG_KEY})
    @DisplayName("비었거나 · 플레이스홀더가 풀리지 않았거나 · base64 가 아니거나 · 32바이트가 아니면 기동하지 않는다. 메시지에 키 내용이 없다")
    void invalidKeyRejected(String key) {
        assertBindFails(Map.of(SECRET_KEY, key), "AUTH_OTP_SECRET_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"${AUTH_OTP_SECRET_KEY_PREVIOUS}", "not base64!!", SHORT_KEY})
    @DisplayName("이전 키를 채웠다면 같은 규칙이다")
    void invalidPreviousKeyRejected(String key) {
        assertBindFails(Map.of(SECRET_KEY, KEY, PREVIOUS, key), "AUTH_OTP_SECRET_KEY_PREVIOUS");
    }

    @Test
    @DisplayName("이전 키가 현재 키와 같으면 기동하지 않는다 — 회전을 잘못 적은 것이다")
    void previousEqualToCurrentRejected() {
        assertBindFails(Map.of(SECRET_KEY, KEY, PREVIOUS, KEY), "AUTH_OTP_SECRET_KEY_PREVIOUS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"enrollment-ttl=59s", "enrollment-ttl=61m", "challenge-ttl=59s", "challenge-ttl=11m", "challenge-ttl=0s"})
    @DisplayName("TTL 범위 — 등록 1분~1시간, 챌린지 1분~10분. 벗어나면 기동하지 않는다")
    void ttlRangeRejected(String entry) {
        String[] pair = entry.split("=");
        assertBindFails(Map.of(SECRET_KEY, KEY, "auth.admin-otp." + pair[0], pair[1]), "auth.admin-otp." + pair[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enrollment-ttl=1m", "enrollment-ttl=60m", "challenge-ttl=1m", "challenge-ttl=10m"})
    @DisplayName("TTL 경계값은 받는다")
    void ttlBoundariesAccepted(String entry) {
        String[] pair = entry.split("=");
        assertThat(bind(Map.of(SECRET_KEY, KEY, "auth.admin-otp." + pair[0], pair[1]))).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "Shop:Admin", "${AUTH_OTP_ISSUER}"})
    @DisplayName("발급자가 비었거나 ':' 가 있거나 풀리지 않았으면 기동하지 않는다")
    void invalidIssuerRejected(String issuer) {
        assertBindFails(Map.of(SECRET_KEY, KEY, "auth.admin-otp.issuer", issuer), "auth.admin-otp.issuer");
    }

    @Test
    @DisplayName("저장소의 테스트 키(디코드하면 test-only 포함)는 allow-test-only-key 없이는 기동하지 않는다 — 현재 키든 이전 키든. 기본은 끔이다")
    void testOnlyKeyRejectedUnlessAllowed() {
        assertTestOnlyRejected(Map.of(SECRET_KEY, KEY), "AUTH_OTP_SECRET_KEY");
        assertTestOnlyRejected(Map.of(SECRET_KEY, KEY, ALLOW_TEST_ONLY_KEY, "false"), "AUTH_OTP_SECRET_KEY");
        assertThat(bind(Map.of(SECRET_KEY, KEY)).allowTestOnlyKey()).isTrue();
        assertThat(new AdminOtpProperties(NON_TEST_KEY, null, "Issuer", Duration.ofMinutes(15), Duration.ofMinutes(5), false).secretKeyBytes()).hasSize(32);
        assertThatThrownBy(() -> new AdminOtpProperties(NON_TEST_KEY, OTHER_KEY, "Issuer", Duration.ofMinutes(15), Duration.ofMinutes(5), false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_OTP_SECRET_KEY_PREVIOUS").hasMessageContaining("테스트 키")
            .satisfies(cause -> assertThat(cause.getMessage()).doesNotContain(OTHER_KEY, NON_TEST_KEY));
    }

    @Test
    @DisplayName("toString 은 키를 가린다")
    void toStringMasksKeys() {
        assertThat(bind(Map.of(SECRET_KEY, KEY, PREVIOUS, OTHER_KEY)).toString())
            .contains("secretKey=****", "previousSecretKey=****", "issuer=Commerce Foundry Admin")
            .doesNotContain(KEY, OTHER_KEY);
    }

    private static void assertTestOnlyRejected(Map<String, String> properties, String messagePart) {
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(new HashMap<>(properties))).bindOrCreate("auth.admin-otp", AdminOtpProperties.class))
            .isInstanceOf(BindException.class)
            .rootCause().isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(messagePart).hasMessageContaining("테스트 키")
            .satisfies(cause -> assertThat(cause.getMessage()).doesNotContain(KEY));
    }

    private static void assertBindFails(Map<String, String> properties, String messagePart) {
        assertThatThrownBy(() -> bind(properties))
            .isInstanceOf(BindException.class)
            .rootCause().isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(messagePart)
            .satisfies(cause -> assertThat(cause.getMessage()).doesNotContain(KEY, OTHER_KEY, SHORT_KEY, LONG_KEY));
    }

    /** 여기 키는 전부 테스트 키라 허용을 켜고 묶는다 — 켜지 않은 경우는 {@link #testOnlyKeyRejectedUnlessAllowed}. */
    private static AdminOtpProperties bind(Map<String, String> properties) {
        Map<String, String> withAllow = new HashMap<>(properties);
        withAllow.putIfAbsent(ALLOW_TEST_ONLY_KEY, "true");
        return new Binder(new MapConfigurationPropertySource(withAllow)).bindOrCreate("auth.admin-otp", AdminOtpProperties.class);
    }
}
