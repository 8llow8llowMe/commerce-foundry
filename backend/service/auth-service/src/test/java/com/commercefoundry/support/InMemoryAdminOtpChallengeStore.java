package com.commercefoundry.support;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpChallengeStorePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 컨텍스트 흐름 테스트용 관리자 로그인 2단계 챌린지 저장소 (#238 PR-B). SET NX PX · GET · GETDEL 을 메모리로 흉내 낸다 — 소비는 {@link Map#remove} 한 번이라
 * 동시에 불러도 한쪽만 값을 받는다. Redis TTL 대신 주입된 {@link Clock} 으로 만료를 재현한다(지금이 만료 시각을 지났으면 없다).
 * 실제 키 · TTL · GETDEL 은 {@code AdminOtpChallengeRedisAdapterTest}(Testcontainers)가 본다.
 * {@link #unavailable} 을 켜면 모든 메서드가 저장소 장애({@code AUTH_013})를 던진다.
 */
public class InMemoryAdminOtpChallengeStore implements AdminOtpChallengeStorePort {

    private record Entry(AdminOtpChallenge challenge, Instant expiresAt) {

        private boolean expiredAt(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    private final Map<String, Entry> challenges = new ConcurrentHashMap<>();
    private final Clock clock;

    public volatile boolean unavailable;

    public InMemoryAdminOtpChallengeStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean save(String challengeHash, AdminOtpChallenge challenge, Duration ttl) {
        failIfUnavailable();
        Instant now = clock.instant();
        Entry created = new Entry(challenge, now.plus(ttl));
        Entry stored = challenges.compute(challengeHash, (key, current) -> current == null || current.expiredAt(now) ? created : current);
        return stored == created;
    }

    @Override
    public Optional<AdminOtpChallenge> find(String challengeHash) {
        failIfUnavailable();
        Entry entry = challenges.get(challengeHash);
        return entry == null || entry.expiredAt(clock.instant()) ? Optional.empty() : Optional.of(entry.challenge());
    }

    @Override
    public Optional<AdminOtpChallenge> consume(String challengeHash) {
        failIfUnavailable();
        Entry entry = challenges.remove(challengeHash);
        return entry == null || entry.expiredAt(clock.instant()) ? Optional.empty() : Optional.of(entry.challenge());
    }

    /** 남아 있는(만료 전) 챌린지 수. */
    public long size() {
        Instant now = clock.instant();
        return challenges.values().stream().filter(entry -> !entry.expiredAt(now)).count();
    }

    /** 저장된 키(해시)들 — 원문이 저장되지 않았음을 확인할 때 쓴다. */
    public Set<String> hashes() {
        return Map.copyOf(challenges).keySet();
    }

    /** 테스트 사이에 비운다 — 컨텍스트를 같이 쓰는 다른 테스트에 번지지 않게. */
    public void clear() {
        challenges.clear();
        unavailable = false;
    }

    private void failIfUnavailable() {
        if (unavailable) {
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
    }
}
