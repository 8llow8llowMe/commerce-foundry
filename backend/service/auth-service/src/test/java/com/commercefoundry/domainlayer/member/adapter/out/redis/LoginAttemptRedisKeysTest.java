package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.support.TestLoginGuard;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginAttemptRedisKeysTest {

    private static final String EMAIL = "member@example.com";

    private final LoginAttemptKeyHasher hasher = new LoginAttemptKeyHasher(TestLoginGuard.properties());

    @Test
    @DisplayName("로그인은 auth:login-attempt:{22자}, 관리자 로그인은 auth:admin-login-attempt:{22자}, 회원가입은 auth:sign-up-attempt:{22자}이고 이메일 원문이 없다")
    void keyFormat() {
        String hash = hasher.hash(EMAIL);

        assertThat(LoginAttemptRedisKeys.attempt(AttemptKind.LOGIN, hash).value())
            .isEqualTo("auth:login-attempt:" + hash)
            .matches("auth:login-attempt:[A-Za-z0-9_-]{22}")
            .doesNotContain("@", "member", "example");
        assertThat(LoginAttemptRedisKeys.attempt(AttemptKind.ADMIN_LOGIN, hash).value())
            .isEqualTo("auth:admin-login-attempt:" + hash)
            .matches("auth:admin-login-attempt:[A-Za-z0-9_-]{22}")
            .doesNotContain("@", "member", "example");
        assertThat(LoginAttemptRedisKeys.attempt(AttemptKind.SIGN_UP, hash).value())
            .isEqualTo("auth:sign-up-attempt:" + hash)
            .matches("auth:sign-up-attempt:[A-Za-z0-9_-]{22}")
            .doesNotContain("@", "member", "example");
    }

    @Test
    @DisplayName("종류마다 키가 다르다 — 같은 이메일이라도 로그인 · 회원가입이 한 키를 나눠 쓰지 않는다")
    void distinctPerKind() {
        String hash = hasher.hash(EMAIL);

        assertThat(Arrays.stream(AttemptKind.values()).map(kind -> LoginAttemptRedisKeys.attempt(kind, hash).value()))
            .doesNotHaveDuplicates()
            .hasSize(AttemptKind.values().length);
    }
}
