package com.commercefoundry.security.token.revocation;

import com.commercefoundry.redis.key.RedisKey;
import com.commercefoundry.security.issuer.JwtIssuanceProperties;
import java.time.Duration;

/**
 * 로그아웃한 access 토큰의 차단 목록 키 ({@code auth:revoked-access-token:{jti}}) 와 보관 기간.
 *
 * <p>auth-service 가 로그아웃 때 쓰고 api-gateway 가 요청마다 읽는 서비스 횡단 키다. 형식이 두 곳에서 갈라지지 않게
 * 양쪽이 의존하는 이 모듈의 함수 하나로만 만든다 ({@code coding-conventions.md} §9-7). 값은 뜻이 없다(존재만 본다).
 *
 * <p>redis-core 는 컴파일에만 쓴다. 이 클래스는 redis-core 를 직접 의존하는 auth · api-gateway 에서만 부른다.
 */
public final class RevokedAccessTokenKeys {

    private static final String OWNER = "auth";
    private static final String DOMAIN = "revoked-access-token";

    private RevokedAccessTokenKeys() {
    }

    /** {@code jti} 는 발급기가 만든 UUID 다. {@link RedisKey} 의 id 규칙에 맞지 않으면 {@link IllegalArgumentException}. */
    public static RedisKey of(String jti) {
        return RedisKey.of(OWNER, DOMAIN, jti);
    }

    /**
     * 차단 항목의 TTL — access 토큰 수명 <b>상한</b>({@link JwtIssuanceProperties#MAX_ACCESS_TOKEN_TTL}) + 시계 오차 허용.
     *
     * <p>규칙은 "남은 수명 + 시계 오차 이상" 이다. 로그아웃 시점에는 {@code MemberPrincipal} 에 {@code exp} 가 없어 남은 수명 대신 상한을 쓴다.
     * 설정값({@code access-token-ttl})을 쓰지 않는 이유 — TTL 을 줄여 배포한 직후에는 줄이기 전에 발급된 더 긴 토큰이 살아 있다.
     * 차단 항목이 그 토큰보다 먼저 사라지면 로그아웃한 토큰이 다시 통과한다. 상한을 쓰면 설정이 어떻게 바뀌어도 먼저 사라지지 않는다.
     *
     * @param clockSkew 검증 쪽 시계 오차 허용 ({@code security.jwt.clock-skew}). 만료 판정이 그만큼 늦게 일어난다
     */
    public static Duration ttl(Duration clockSkew) {
        if (clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalArgumentException("clockSkew 는 0 이상이어야 합니다");
        }
        return JwtIssuanceProperties.MAX_ACCESS_TOKEN_TTL.plus(clockSkew);
    }
}
