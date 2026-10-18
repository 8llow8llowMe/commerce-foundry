package com.commercefoundry.support;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.domainlayer.member.application.port.out.RefreshTokenStorePort;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 컨텍스트 흐름 테스트용 토큰 저장소(refresh 토큰 + 로그아웃 차단 목록). Lua 스크립트({@code RefreshTokenRedisScripts#ROTATE})와 같은 판정을 메모리로 흉내 낸다 —
 * 유예 경계(지금 − rotatedAt ≤ 유예, 음수는 0) · 형제 상한(넘으면 재사용 감지) 모두 같다({@code InMemoryRefreshTokenStoreTest} 가 고정한다).
 * TTL 은 기록만 하고 만료시키지 않는다. 실제 Lua · 키 · TTL 은 {@code RefreshTokenRedisAdapterTest}(Testcontainers)가 본다.
 *
 * <p>{@link #unavailable} 을 켜면 모든 메서드가 저장소 장애({@code AUTH_013})를 던진다 — 어댑터가 Redis 오류를 바꾸는 모양 그대로다.
 */
public class InMemoryRefreshTokenStore implements RefreshTokenStorePort, AccessTokenRevocationPort {

    private record StoredToken(long memberId, String familyId, Instant rotatedAt, int siblings) {
    }

    private final Map<String, StoredToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, Duration> families = new ConcurrentHashMap<>();
    private final Set<String> revokedAccessTokens = ConcurrentHashMap.newKeySet();

    public volatile boolean unavailable;

    @Override
    public void saveNewFamily(String fingerprint, long memberId, String familyId, Duration ttl) {
        failIfUnavailable();
        tokens.put(fingerprint, new StoredToken(memberId, familyId, null, 0));
        families.put(familyId, ttl);
    }

    @Override
    public Optional<RefreshTokenSession> findSession(String fingerprint) {
        failIfUnavailable();
        StoredToken token = tokens.get(fingerprint);
        if (token == null || !families.containsKey(token.familyId())) {
            return Optional.empty();
        }
        return Optional.of(new RefreshTokenSession(fingerprint, token.memberId(), token.familyId()));
    }

    @Override
    public synchronized RefreshTokenRotation rotate(RefreshTokenSession session, String newFingerprint, Instant now, RefreshTokenRotationPolicy policy) {
        failIfUnavailable();
        StoredToken token = tokens.get(session.fingerprint());
        if (token == null || token.memberId() != session.memberId() || !token.familyId().equals(session.familyId()) || !families.containsKey(session.familyId())) {
            return RefreshTokenRotation.INVALID;
        }
        RefreshTokenRotation result = RefreshTokenRotation.ROTATED;
        if (token.rotatedAt() != null) {
            long elapsed = Math.max(0, Duration.between(token.rotatedAt(), now).toMillis());
            if (elapsed > policy.reuseGrace().toMillis()) {
                families.remove(session.familyId());
                return RefreshTokenRotation.REUSE_DETECTED;
            }
            int siblings = token.siblings() + 1;
            tokens.put(session.fingerprint(), new StoredToken(token.memberId(), token.familyId(), token.rotatedAt(), siblings));
            if (siblings > policy.maxSiblings()) {
                families.remove(session.familyId());
                return RefreshTokenRotation.REUSE_DETECTED;
            }
            result = RefreshTokenRotation.REISSUED_IN_GRACE;
        } else {
            tokens.put(session.fingerprint(), new StoredToken(token.memberId(), token.familyId(), now, 0));
        }
        tokens.put(newFingerprint, new StoredToken(session.memberId(), session.familyId(), null, 0));
        families.put(session.familyId(), policy.ttl());
        return result;
    }

    @Override
    public void revokeFamily(String familyId) {
        failIfUnavailable();
        families.remove(familyId);
    }

    @Override
    public void revoke(String tokenId) {
        failIfUnavailable();
        revokedAccessTokens.add(tokenId);
    }

    public boolean accessTokenRevoked(String tokenId) {
        return revokedAccessTokens.contains(tokenId);
    }

    public boolean familyAlive(String familyId) {
        return families.containsKey(familyId);
    }

    private void failIfUnavailable() {
        if (unavailable) {
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
    }
}
