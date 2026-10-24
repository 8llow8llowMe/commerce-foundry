package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.port.out.RefreshTokenStorePort;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationResult;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * refresh 토큰 저장소(Redis). 키는 {@link RefreshTokenRedisKeys}, 원자 처리는 {@link RefreshTokenRedisScripts} 다.
 * Boot 가 만드는 {@link StringRedisTemplate} 만 쓴다 ({@code coding-conventions.md} §9-7). Redis 오류는 {@link TokenStoreRedisSupport} 가 {@code AUTH_013} 으로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenRedisAdapter implements RefreshTokenStorePort {

    private final StringRedisTemplate redisTemplate;
    private final TokenStoreRedisSupport tokenStore;

    @Override
    public void saveNewFamily(String fingerprint, long memberId, String familyId, Instant loginAt, Duration ttl) {
        List<String> keys = List.of(RefreshTokenRedisKeys.token(fingerprint).value(), RefreshTokenRedisKeys.family(familyId).value());
        tokenStore.execute(() -> redisTemplate.execute(
            RefreshTokenRedisScripts.SAVE_NEW_FAMILY, keys, String.valueOf(memberId), familyId, millis(ttl), String.valueOf(loginAt.toEpochMilli())
        ));
    }

    /**
     * HMGET 으로 주인 · family 를 읽고 family 가 살아 있는지 본다. 두 명령 사이에 바뀌어도 괜찮다 — 회전 스크립트가 다시 확인한다.
     * 저장된 값이 규칙 밖(숫자 아닌 memberId · 키 규칙 밖 familyId)이면 없는 토큰과 같다.
     */
    @Override
    public Optional<RefreshTokenSession> findSession(String fingerprint) {
        String tokenKey = RefreshTokenRedisKeys.token(fingerprint).value();
        return tokenStore.execute(() -> {
            List<Object> stored = redisTemplate.opsForHash().multiGet(tokenKey, List.<Object>of(RefreshTokenRedisScripts.MEMBER_ID, RefreshTokenRedisScripts.FAMILY_ID));
            Optional<RefreshTokenSession> session = toSession(fingerprint, stored);
            if (session.isEmpty() || !Boolean.TRUE.equals(redisTemplate.hasKey(RefreshTokenRedisKeys.family(session.get().familyId()).value()))) {
                return Optional.empty();
            }
            return session;
        });
    }

    @Override
    public RefreshTokenRotationResult rotate(RefreshTokenSession session, String newFingerprint, Instant now, RefreshTokenRotationPolicy policy) {
        List<String> keys = List.of(
            RefreshTokenRedisKeys.token(session.fingerprint()).value(),
            RefreshTokenRedisKeys.family(session.familyId()).value(),
            RefreshTokenRedisKeys.token(newFingerprint).value()
        );
        List<Object> result = tokenStore.execute(() -> redisTemplate.execute(
            RefreshTokenRedisScripts.ROTATE, keys,
            String.valueOf(session.memberId()), session.familyId(), String.valueOf(now.toEpochMilli()),
            millis(policy.reuseGrace()), millis(policy.ttl()), String.valueOf(policy.maxSiblings()), millis(policy.familyMaxLifetime())
        ));
        return toRotationResult(result);
    }

    @Override
    public void revokeFamily(String familyId) {
        String familyKey = RefreshTokenRedisKeys.family(familyId).value();
        tokenStore.execute(() -> redisTemplate.delete(familyKey));
    }

    /** 스크립트 반환 {@code {이름, TTL(ms)}} — 이름은 문자열, TTL 은 정수 응답(Long)이다. 모양이 다르면 스크립트와 코드가 어긋난 것이라 그대로 실패한다. */
    static RefreshTokenRotationResult toRotationResult(List<Object> result) {
        if (result == null || result.size() != 2 || !(result.get(0) instanceof String name) || !(result.get(1) instanceof Number ttlMillis)) {
            throw new IllegalStateException("refresh 토큰 회전 스크립트의 결과 모양이 다릅니다: " + result);
        }
        return new RefreshTokenRotationResult(RefreshTokenRotation.valueOf(name), Duration.ofMillis(ttlMillis.longValue()));
    }

    private static Optional<RefreshTokenSession> toSession(String fingerprint, List<Object> stored) {
        if (stored == null || stored.size() != 2 || !(stored.get(0) instanceof String memberId) || !(stored.get(1) instanceof String familyId)) {
            return Optional.empty();
        }
        try {
            RefreshTokenRedisKeys.family(familyId);
            return Optional.of(new RefreshTokenSession(fingerprint, Long.parseLong(memberId), familyId));
        } catch (IllegalArgumentException outOfRule) {
            return Optional.empty();
        }
    }

    private static String millis(Duration duration) {
        return String.valueOf(duration.toMillis());
    }
}
