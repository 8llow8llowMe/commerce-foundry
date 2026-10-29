package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Base32Test {

    @ParameterizedTest(name = "\"{0}\" ↔ {1}")
    @CsvSource(value = {"'', ''", "f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"})
    @DisplayName("RFC 4648 §10 테스트 벡터 (패딩을 뺀 값)")
    void rfc4648Vectors(String plain, String encoded) {
        byte[] bytes = plain.getBytes(StandardCharsets.US_ASCII);

        assertThat(Base32.encode(bytes)).isEqualTo(encoded);
        assertThat(Base32.decode(encoded)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("임의의 20바이트 비밀이 32자로 왕복한다")
    void roundTripsSecret() {
        byte[] secret = new byte[Totp.SECRET_BYTES];
        new SecureRandom().nextBytes(secret);

        String encoded = Base32.encode(secret);

        assertThat(encoded).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Base32.decode(encoded)).isEqualTo(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"my", "MZXW6===", "MZXW6=", "MZ XW", "MZXW1", "MZXW8", "MZXW6Y-", "M", "MZX", "MZXW6Y", "MZ", "MZXR"})
    @DisplayName("소문자 · 패딩 · 공백 · 알파벳 밖 문자 · 남는 길이 · 0 이 아닌 남는 비트는 거절한다")
    void rejectsNonCanonical(String text) {
        assertThatThrownBy(() -> Base32.decode(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 은 거절한다")
    void rejectsNull() {
        assertThatThrownBy(() -> Base32.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
