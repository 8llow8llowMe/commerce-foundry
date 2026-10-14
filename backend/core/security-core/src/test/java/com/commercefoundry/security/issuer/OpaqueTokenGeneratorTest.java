package com.commercefoundry.security.issuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OpaqueTokenGeneratorTest {

    private static final String URL_SAFE_43 = "^[A-Za-z0-9_-]{43}$";

    private final OpaqueTokenGenerator generator = new OpaqueTokenGenerator();

    @Test
    @DisplayName("토큰은 32바이트 난수의 base64url 무패딩 — URL · 쿠키 · Redis 키에 그대로 쓸 수 있는 43자다")
    void generatesUrlSafeToken() {
        assertThat(generator.generate()).matches(URL_SAFE_43);
    }

    @Test
    @DisplayName("매번 다른 값을 낸다")
    void generatesDistinctTokens() {
        Set<String> tokens = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> tokens.add(generator.generate()));

        assertThat(tokens).hasSize(1_000);
    }

    @Test
    @DisplayName("핑거프린트는 SHA-256 의 base64url 무패딩이고 같은 입력이면 항상 같다")
    void fingerprintIsDeterministicSha256() {
        String token = generator.generate();

        assertThat(generator.fingerprint(token)).matches(URL_SAFE_43).isEqualTo(new OpaqueTokenGenerator().fingerprint(token));
        assertThat(generator.fingerprint(token)).isNotEqualTo(token).isNotEqualTo(generator.fingerprint(generator.generate()));
        // RFC 6234 의 SHA-256("abc") 시험 벡터를 base64url 로 옮긴 값
        assertThat(generator.fingerprint("abc")).isEqualTo("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("빈 토큰의 핑거프린트는 만들지 않는다 — 빈 값끼리 같은 키로 부딪힌다")
    void rejectsBlankToken(String token) {
        assertThatThrownBy(() -> generator.fingerprint(token)).isInstanceOf(IllegalArgumentException.class);
    }
}
