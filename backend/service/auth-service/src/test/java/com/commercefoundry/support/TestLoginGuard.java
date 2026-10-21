package com.commercefoundry.support;

import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import java.time.Duration;

/**
 * 시도 제한 테스트가 같이 쓰는 값. 키는 {@code application-test.yml} 의 {@code auth.login-guard.hash-key} 와 같다.
 * 값은 전부 테스트 전용이다. 비밀이 아니다.
 */
public final class TestLoginGuard {

    /** base64("auth-login-guard-test-only-hash-key") — 35바이트. */
    public static final String HASH_KEY = "YXV0aC1sb2dpbi1ndWFyZC10ZXN0LW9ubHktaGFzaC1rZXk=";

    /** base64("another-auth-login-guard-test-only-key") — 38바이트. 키가 다르면 해시가 다른지 볼 때 쓴다. */
    public static final String OTHER_HASH_KEY = "YW5vdGhlci1hdXRoLWxvZ2luLWd1YXJkLXRlc3Qtb25seS1rZXk=";

    private TestLoginGuard() {
    }

    /** 켜고 테스트 키를 넣은 기본값(5회 · 15분). */
    public static AuthLoginGuardProperties properties() {
        return properties(HASH_KEY);
    }

    public static AuthLoginGuardProperties properties(String hashKey) {
        return new AuthLoginGuardProperties(true, 5, Duration.ofMinutes(15), hashKey);
    }
}
