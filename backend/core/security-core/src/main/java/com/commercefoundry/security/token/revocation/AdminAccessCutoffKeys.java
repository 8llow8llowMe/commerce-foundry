package com.commercefoundry.security.token.revocation;

import com.commercefoundry.redis.key.RedisKey;
import java.time.Instant;

/**
 * 관리자 access 토큰을 한꺼번에 끊는 시각 키 ({@code auth:admin-access-cutoff:{memberId}}, #193).
 *
 * <p>auth-service 가 비밀번호 변경 · 비활성화 때 쓰고 api-gateway 가 요청마다 읽는다. 값은 cutoff 시각의 epoch milli 다.
 * 그 시각보다 이르게 발급된 관리자 access 토큰은 차단한다. TTL 은 로그아웃 차단 목록과 같다 ({@link RevokedAccessTokenKeys#ttl}).
 */
public final class AdminAccessCutoffKeys {

    private static final String OWNER = "auth";
    private static final String DOMAIN = "admin-access-cutoff";

    private AdminAccessCutoffKeys() {
    }

    public static RedisKey of(long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId 는 양수여야 합니다");
        }
        return RedisKey.of(OWNER, DOMAIN, Long.toString(memberId));
    }

    /** Redis 값(epoch milli)을 시각으로 읽는다. 숫자가 아니면 {@link IllegalArgumentException}. */
    public static Instant parseEpochMilli(String epochMilli) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(epochMilli));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("admin access cutoff 값이 숫자가 아닙니다");
        }
    }
}
