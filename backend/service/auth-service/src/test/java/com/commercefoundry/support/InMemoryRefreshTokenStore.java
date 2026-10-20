package com.commercefoundry.support;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.domainlayer.member.application.port.out.RefreshTokenStorePort;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationResult;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 컨텍스트 흐름 테스트용 토큰 저장소(refresh 토큰 + 로그아웃 차단 목록). Lua 스크립트({@code RefreshTokenRedisScripts#ROTATE})와 같은 판정을 메모리로 흉내 낸다 —
 * 저장된 시각은 정수 문자열 · 유한 · 지금 + 허용 시계 오차 이하만(아니면 family 값은 FAMILY_CORRUPTED, rotatedAt 은 재사용 감지) ·
 * 절대 수명(남은 시간 1초 미만이면 만료, 경과 음수는 0) · 유예 경계(지금 − rotatedAt ≤ 유예, 음수는 0) · 형제 상한(넘으면 재사용 감지) ·
 * 새 TTL {@code min(TTL, 상한 − 경과)} 모두 같다({@code InMemoryRefreshTokenStoreTest} 가 고정한다).
 * TTL 은 기록만 하고 만료시키지 않는다. 실제 Lua · 키 · TTL 은 {@code RefreshTokenRedisAdapterTest}(Testcontainers)가 본다.
 *
 * <p>{@link #unavailable} 을 켜면 모든 메서드가 저장소 장애({@code AUTH_013})를 던진다 — 어댑터가 Redis 오류를 바꾸는 모양 그대로다.
 */
public class InMemoryRefreshTokenStore implements RefreshTokenStorePort, AccessTokenRevocationPort {

    /** rotatedAt 은 Redis 해시 필드처럼 문자열(epoch ms)로 둔다 — 손상 값 판정을 Lua 와 같게 본다. */
    private record StoredToken(long memberId, String familyId, String rotatedAt, int siblings) {
    }

    /** Redis family 키의 값(로그인 시각 epoch ms 문자열)과 TTL. */
    private record StoredFamily(String loginAt, Duration ttl) {
    }

    /** Lua {@code string.match(v, '^%d+$')} 와 같다 — {@code "nan"} · {@code "1e3"} · {@code "0x10"} 은 받지 않는다. Java 정규식의 숫자 클래스는 ASCII 숫자만이다. */
    private static final Pattern EPOCH_MILLIS = Pattern.compile("\\d+");

    private final Map<String, StoredToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, StoredFamily> families = new ConcurrentHashMap<>();
    private final Set<String> revokedAccessTokens = ConcurrentHashMap.newKeySet();

    public volatile boolean unavailable;

    @Override
    public void saveNewFamily(String fingerprint, long memberId, String familyId, Instant loginAt, Duration ttl) {
        failIfUnavailable();
        tokens.put(fingerprint, new StoredToken(memberId, familyId, null, 0));
        families.put(familyId, new StoredFamily(String.valueOf(loginAt.toEpochMilli()), ttl));
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
    public synchronized RefreshTokenRotationResult rotate(RefreshTokenSession session, String newFingerprint, Instant now, RefreshTokenRotationPolicy policy) {
        failIfUnavailable();
        StoredToken token = tokens.get(session.fingerprint());
        StoredFamily family = families.get(session.familyId());
        if (token == null || token.memberId() != session.memberId() || !token.familyId().equals(session.familyId()) || family == null) {
            return RefreshTokenRotationResult.rejected(RefreshTokenRotation.INVALID);
        }
        long latest = now.toEpochMilli() + RefreshTokenRotationPolicy.CLOCK_SKEW.toMillis();
        Long loginAt = epochMillis(family.loginAt(), latest);
        if (loginAt == null) {
            families.remove(session.familyId());
            return RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED);
        }
        long age = Math.max(0, now.toEpochMilli() - loginAt);
        long remaining = policy.familyMaxLifetime().toMillis() - age;
        if (remaining < RefreshTokenRotationPolicy.MIN_REMAINING_LIFETIME.toMillis()) {
            families.remove(session.familyId());
            return RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED);
        }
        Duration ttl = Duration.ofMillis(Math.min(policy.ttl().toMillis(), remaining));
        RefreshTokenRotation result = RefreshTokenRotation.ROTATED;
        if (token.rotatedAt() != null) {
            Long rotatedAt = epochMillis(token.rotatedAt(), latest);
            if (rotatedAt == null) {
                families.remove(session.familyId());
                return RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED);
            }
            long elapsed = Math.max(0, now.toEpochMilli() - rotatedAt);
            if (elapsed > policy.reuseGrace().toMillis()) {
                families.remove(session.familyId());
                return RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED);
            }
            int siblings = token.siblings() + 1;
            tokens.put(session.fingerprint(), new StoredToken(token.memberId(), token.familyId(), token.rotatedAt(), siblings));
            if (siblings > policy.maxSiblings()) {
                families.remove(session.familyId());
                return RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED);
            }
            result = RefreshTokenRotation.REISSUED_IN_GRACE;
        } else {
            tokens.put(session.fingerprint(), new StoredToken(token.memberId(), token.familyId(), String.valueOf(now.toEpochMilli()), 0));
        }
        tokens.put(newFingerprint, new StoredToken(session.memberId(), session.familyId(), null, 0));
        families.put(session.familyId(), new StoredFamily(family.loginAt(), ttl));
        return new RefreshTokenRotationResult(result, ttl);
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

    /** family 의 로그인 시각 — 회전해도 그대로인지 본다. 없으면 null. */
    public Instant familyLoginAt(String familyId) {
        StoredFamily family = families.get(familyId);
        return family == null ? null : Instant.ofEpochMilli(Long.parseLong(family.loginAt()));
    }

    /** family 값을 손상시킨다 — Redis 에 정수가 아닌 값 · 먼 미래 값이 들어간 경우를 흉내 낸다. */
    public void corruptFamilyValue(String familyId, String value) {
        families.computeIfPresent(familyId, (id, family) -> new StoredFamily(value, family.ttl()));
    }

    /** 토큰의 rotatedAt 을 손상시킨다. */
    public void corruptRotatedAt(String fingerprint, String value) {
        tokens.computeIfPresent(fingerprint, (key, token) -> new StoredToken(token.memberId(), token.familyId(), value, token.siblings()));
    }

    /** family 에 마지막으로 건 TTL. 없으면 null. */
    public Duration familyTtl(String familyId) {
        StoredFamily family = families.get(familyId);
        return family == null ? null : family.ttl();
    }

    /**
     * Lua {@code epochMillis} 와 같다 — 정수 문자열이 아니거나 · {@code tonumber} 가 무한대로 읽거나(309자리 이상) · {@code latest} 보다 크면 null.
     * Lua 처럼 double 로 읽는다 — {@code Long.parseLong} 은 19자리를 넘으면 예외라 Lua 와 결과가 갈린다. 통과한 값은 {@code latest} 이하라 long 에 정확히 들어간다.
     */
    private static Long epochMillis(String value, long latest) {
        if (!EPOCH_MILLIS.matcher(value).matches()) {
            return null;
        }
        double millis = Double.parseDouble(value);
        if (Double.isInfinite(millis) || millis > latest) {
            return null;
        }
        return (long) millis;
    }

    private void failIfUnavailable() {
        if (unavailable) {
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
    }
}
