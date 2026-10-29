package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TotpTest {

    /** RFC 6238 부록 B 의 SHA1 비밀 — ASCII "12345678901234567890". */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest(name = "T={0} → {1}")
    @CsvSource({
        "59, 287082",
        "1111111109, 081804",
        "1111111111, 050471",
        "1234567890, 005924",
        "2000000000, 279037",
        "20000000000, 353130"
    })
    @DisplayName("RFC 6238 부록 B SHA1 벡터의 뒤 6자리와 같다 (앞자리 0 포함)")
    void rfc6238Vectors(long unixSeconds, String expected) {
        long step = Totp.timeStep(Instant.ofEpochSecond(unixSeconds));

        assertThat(Totp.code(RFC_SECRET, step)).isEqualTo(expected);
        assertThat(Totp.verify(RFC_SECRET, expected, step)).hasValue(step);
    }

    @Test
    @DisplayName("step 은 30초 단위다 — 29초와 0초는 같은 step, 30초는 다음 step")
    void timeStepIsThirtySeconds() {
        assertThat(Totp.timeStep(Instant.ofEpochSecond(0))).isZero();
        assertThat(Totp.timeStep(Instant.ofEpochSecond(29))).isZero();
        assertThat(Totp.timeStep(Instant.ofEpochSecond(30))).isEqualTo(1);
    }

    @Test
    @DisplayName("±1 step 안의 코드는 받고 일치한 step 을 돌려준다")
    void acceptsAdjacentSteps() {
        long now = 1_000_000;

        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now)).hasValue(now - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now), now)).hasValue(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now)).hasValue(now + 1);
    }

    @Test
    @DisplayName("창 밖(±2 step) 코드는 거절한다 — 창 0 이면 지금 step 만 받는다")
    void rejectsOutsideWindow() {
        long now = 1_000_000;

        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now - 2), now)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now + 2), now)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now), now, 0)).hasValue(now);
    }

    @Test
    @DisplayName("다른 비밀의 코드는 거절한다")
    void rejectsOtherSecret() {
        byte[] other = "abcdefghijabcdefghij".getBytes(StandardCharsets.US_ASCII);
        long now = 1_000_000;

        assertThat(Totp.verify(other, Totp.code(RFC_SECRET, now), now)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "12345", "1234567", "12345a", " 23456", "１２３４５６"})
    @DisplayName("6자리 ASCII 숫자가 아니면 계산 없이 거절한다")
    void rejectsMalformedCode(String code) {
        assertThat(Totp.verify(RFC_SECRET, code, 1_000_000)).isEmpty();
    }

    @Test
    @DisplayName("창은 0~2, step 은 0 이상, 비밀은 비어 있지 않아야 한다")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> Totp.verify(RFC_SECRET, "123456", 10, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Totp.verify(RFC_SECRET, "123456", 10, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Totp.code(RFC_SECRET, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Totp.code(new byte[0], 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기본 로케일이 ASCII 가 아닌 숫자(태국 숫자)여도 코드는 ASCII 숫자 6자리다 — 앞자리 0 포함, 검증도 그대로 맞는다")
    void codeIgnoresDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
            // 전제: 이 로케일의 String.format 은 ASCII 가 아닌 숫자를 낸다 — 그래야 이 테스트가 옛 구현을 잡는다
            assertThat(String.format("%06d", 5924)).isNotEqualTo("005924");

            long step = Totp.timeStep(Instant.ofEpochSecond(1234567890L));
            assertThat(Totp.code(RFC_SECRET, step)).isEqualTo("005924").matches("[0-9]{6}");
            assertThat(Totp.verify(RFC_SECRET, "005924", step)).hasValue(step);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("step 0 근처에서는 음수 step 을 건너뛴다")
    void skipsNegativeSteps() {
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, 0), 0)).hasValue(0);
    }

    @Test
    @DisplayName("등록 URI — 라벨 발급자:계정 · issuer 를 퍼센트 인코딩(공백 %20, @ %40)하고 SHA1 · 6자리 · 30초를 밝힌다")
    void provisioningUri() {
        String uri = Totp.provisioningUri("Commerce Foundry Admin", "owner+shop@example.com", "GEZDGNBVGY3TQOJQ");

        assertThat(uri).isEqualTo("otpauth://totp/Commerce%20Foundry%20Admin:owner%2Bshop%40example.com?secret=GEZDGNBVGY3TQOJQ"
            + "&issuer=Commerce%20Foundry%20Admin&algorithm=SHA1&digits=6&period=30");
        assertThat(Totp.provisioningUri("모코유리", "a&b=c@example.com", "AAAA")).contains("%EB%AA%A8", "a%26b%3Dc%40example.com").doesNotContain("+");
    }

    @Test
    @DisplayName("등록 URI — 발급자에 ':' 가 있거나 비었으면 · 계정이 비었으면 거절한다(라벨 구분자가 둘이 된다)")
    void provisioningUriRejectsBadLabel() {
        assertThatThrownBy(() -> Totp.provisioningUri("Shop:Admin", "a@example.com", "AAAA")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Totp.provisioningUri(" ", "a@example.com", "AAAA")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Totp.provisioningUri("Shop", "", "AAAA")).isInstanceOf(IllegalArgumentException.class);
    }
}
