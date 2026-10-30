package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAccessCutoffPort;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.revocation.AdminAccessCutoffKeys;
import com.commercefoundry.security.token.revocation.RevokedAccessTokenKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 access 컷오프 ({@code auth:admin-access-cutoff:{memberId}}). 값은 epoch milli, TTL 은 로그아웃 차단 목록과 같다 (#193).
 * 쓰기는 비밀번호 변경 · 비활성화, 읽기는 관리자 로그인 2단계(#238 PR-B)다 — 읽기 규칙(값 해석)은 게이트웨이와 같은 {@link AdminAccessCutoffKeys} 를 쓴다.
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

    /** 숫자가 아닌 값은 {@code AUTH_013} 이다 — 게이트웨이가 같은 값을 오류로 올리는 것과 맞춘다. 값 · 키는 예외에 싣지 않는다. */
    @Override
    public Optional<Instant> findCutoff(long memberId) {
        String key = AdminAccessCutoffKeys.of(memberId).value();
        String value = tokenStore.execute(() -> redisTemplate.opsForValue().get(key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(AdminAccessCutoffKeys.parseEpochMilli(value));
        } catch (IllegalArgumentException corrupt) {
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
    }
}
