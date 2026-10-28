package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import com.commercefoundry.support.TestLoginGuard;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LoginAttemptKeyHasherTest {

    private static final String EMAIL = "member@example.com";

    private final LoginAttemptKeyHasher hasher = new LoginAttemptKeyHasher(TestLoginGuard.properties());

    @Test
    @DisplayName("이메일 원문(@ · 로컬 파트 · 도메인)이 없는 22자 base64url 값이다")
    void hashHasNoRawEmail() {
        String hash = hasher.hash(EMAIL);

        assertThat(hash).hasSize(22).matches("[A-Za-z0-9_-]+").doesNotContain("@").doesNotContain("member").doesNotContain("example");
    }

    @Test
    @DisplayName("같은 이메일 · 같은 키면 같은 값이다 — auth 인스턴스끼리 같은 창에 모인다. 이메일이 다르면 다른 값이다")
    void sameEmailSameHash() {
        LoginAttemptKeyHasher other = new LoginAttemptKeyHasher(TestLoginGuard.properties());

        assertThat(hasher.hash(EMAIL)).isEqualTo(other.hash(EMAIL));
        assertThat(hasher.hash(EMAIL)).isNotEqualTo(hasher.hash("member2@example.com"));
    }

    @Test
    @DisplayName("키가 다르면 같은 이메일도 다른 값이다 — 키 없이 이메일 목록 대입으로 되돌릴 수 없다")
    void differentKeyDifferentHash() {
        LoginAttemptKeyHasher other = new LoginAttemptKeyHasher(TestLoginGuard.properties(TestLoginGuard.OTHER_HASH_KEY));

        assertThat(hasher.hash(EMAIL)).isNotEqualTo(other.hash(EMAIL));
    }

    @Test
    @DisplayName("비ASCII 이메일은 UTF-8 로 해시한다 — ASCII 로 바꾸면 '?' 로 뭉개져 다른 이메일과 같은 값이 된다")
    void hashesUtf8() {
        assertThat(hasher.hash("회원@예시.한국")).hasSize(22).isNotEqualTo(hasher.hash("??@??.??")).isNotEqualTo(hasher.hash("고객@예시.한국"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "${AUTH_LOGIN_GUARD_HASH_KEY}"})
    @DisplayName("꺼져 있으면 키 없이 만들어지고, hash 를 부르면 IllegalStateException 이다 — 메시지에 이메일을 싣지 않는다")
    void disabledHasherNeedsNoKeyAndRefusesToHash(String key) {
        LoginAttemptKeyHasher disabled = new LoginAttemptKeyHasher(new AuthLoginGuardProperties(false, 5, Duration.ofMinutes(15), key, null));

        assertThatThrownBy(() -> disabled.hash(EMAIL))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(EMAIL)
            .hasMessageNotContaining("member");
    }
}
