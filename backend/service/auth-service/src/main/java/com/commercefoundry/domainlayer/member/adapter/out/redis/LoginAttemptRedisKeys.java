package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.redis.key.RedisKey;

/**
 * 로그인 · 회원가입 시도 계수 키 ({@code coding-conventions.md} §9-7). auth 안에서만 읽고 쓰는 키라 auth 의 이 함수 하나로만 만든다.
 *
 * <ul>
 *   <li>{@code auth:login-attempt:{emailHmac}} — STRING 창 안의 로그인 시도 수</li>
 *   <li>{@code auth:sign-up-attempt:{emailHmac}} — STRING 창 안의 회원가입 시도 수</li>
 * </ul>
 * {@code emailHmac} 은 {@link LoginAttemptKeyHasher} 의 값(base64url 22자)이다 — 이메일 원문은 키에 넣지 않는다.
 * TTL 은 창의 길이이고 첫 시도에만 같은 스크립트에서 건다 ({@link LoginAttemptRedisScripts}).
 */
final class LoginAttemptRedisKeys {

    private static final String OWNER = "auth";

    private LoginAttemptRedisKeys() {
    }

    static RedisKey attempt(AttemptKind kind, String emailHmac) {
        return RedisKey.of(OWNER, domain(kind), emailHmac);
    }

    /** 종류별 키 domain. 종류가 늘면 switch 가 컴파일에서 막는다 — 두 종류가 한 키를 나눠 쓰지 않게. */
    private static String domain(AttemptKind kind) {
        return switch (kind) {
            case LOGIN -> "login-attempt";
            case SIGN_UP -> "sign-up-attempt";
        };
    }
}
