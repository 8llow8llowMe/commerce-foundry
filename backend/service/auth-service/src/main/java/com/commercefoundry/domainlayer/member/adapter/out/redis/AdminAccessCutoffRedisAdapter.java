package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAccessCutoffPort;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.revocation.AdminAccessCutoffKeys;
import com.commercefoundry.security.token.revocation.RevokedAccessTokenKeys;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 access 컷오프 쓰기 ({@code auth:admin-access-cutoff:{memberId}}). 값은 epoch milli, TTL 은 로그아웃 차단 목록과 같다 (#193).
 */
@Component
@RequiredArgsConstructor
public class AdminAccessCutoffRedisAdapter implements AdminAccessCutoffPort {

    private final StringRedisTemplate redisTemplate;
    private final JwtVerificationProperties jwtVerificationProperties;
    private final TokenStoreRedisSupport tokenStore;

    @Override
    public void cutOff(long memberId, Instant cutoff) {
        String key = AdminAccessCutoffKeys.of(memberId).value();
        Duration ttl = RevokedAccessTokenKeys.ttl(jwtVerificationProperties.clockSkew());
        tokenStore.execute(() -> {
            redisTemplate.opsForValue().set(key, Long.toString(cutoff.toEpochMilli()), ttl);
            return null;
        });
    }
}
