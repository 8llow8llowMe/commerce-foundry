package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static com.commercefoundry.domainlayer.member.adapter.out.redis.TokenStoreRedisSupport.execute;

import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.revocation.RevokedAccessTokenKeys;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그아웃 차단 목록 쓰기 ({@code auth:revoked-access-token:{jti}}). 키와 TTL 은 게이트웨이와 같은 security-core {@link RevokedAccessTokenKeys} 로만 만든다 —
 * TTL 은 access 수명 상한(1시간) + 검증 쪽 시계 오차 허용({@code security.jwt.clock-skew}).
 */
@Component
@RequiredArgsConstructor
public class AccessTokenRevocationRedisAdapter implements AccessTokenRevocationPort {

    /** 차단 항목의 값 — 뜻은 없고 게이트웨이는 존재만 본다. */
    static final String REVOKED = "1";

    private final StringRedisTemplate redisTemplate;
    private final JwtVerificationProperties jwtVerificationProperties;

    /** 같은 명령(SET … PX)에서 TTL 을 준다. */
    @Override
    public void revoke(String tokenId) {
        String key = RevokedAccessTokenKeys.of(tokenId).value();
        Duration ttl = RevokedAccessTokenKeys.ttl(jwtVerificationProperties.clockSkew());
        execute(() -> {
            redisTemplate.opsForValue().set(key, REVOKED, ttl);
            return null;
        });
    }
}
