package com.commercefoundry.support;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 컨텍스트 흐름 테스트용 시도 계수기. Lua 스크립트({@code LoginAttemptRedisScripts#RECORD})와 같은 고정 창을 메모리로 흉내 낸다 —
 * 첫 시도에만 창을 걸고, 이어지는 시도는 창을 늘리지 않으며, 남은 시간은 창 끝 − 지금이다({@code InMemoryLoginAttemptStoreTest} 가 고정한다).
 * Redis TTL 대신 주입된 {@link Clock} 으로 만료를 재현한다 — 창 끝이 지금 이하면 새 창이다. 흐름 테스트는 {@link AdjustableClock} 을 넣어 창을 넘긴다.
 * 실제 Lua · 키 · TTL 은 {@code LoginAttemptRedisAdapterTest}(Testcontainers)가 본다.
 *
 * <p>키는 해시하지 않고 종류 · 이메일 그대로 쓴다 — 메모리 밖으로 나가지 않는다.
 * {@link #unavailable} 을 켜면 모든 메서드가 저장소 장애({@code AUTH_013})를 던진다 — 어댑터가 Redis 오류를 바꾸는 모양 그대로다.
 */
public class InMemoryLoginAttemptStore implements LoginAttemptCounterPort {

    private record Key(AttemptKind kind, String normalizedEmail) {
    }

    private record Window(long count, Instant expiresAt) {
    }

    private final Map<Key, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public volatile boolean unavailable;

    public InMemoryLoginAttemptStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized LoginAttemptOutcome record(AttemptKind kind, String normalizedEmail, Duration window) {
        failIfUnavailable();
        Instant now = clock.instant();
        Window updated = windows.compute(new Key(kind, normalizedEmail), (key, current) -> current == null || !current.expiresAt().isAfter(now)
            ? new Window(1, now.plus(window))
            : new Window(current.count() + 1, current.expiresAt()));
        return new LoginAttemptOutcome(updated.count(), Duration.between(now, updated.expiresAt()));
    }

    @Override
    public void reset(AttemptKind kind, String normalizedEmail) {
        failIfUnavailable();
        windows.remove(new Key(kind, normalizedEmail));
    }

    /** 지금 창 안의 시도 수. 창이 없거나 지났으면 0. */
    public long attempts(AttemptKind kind, String normalizedEmail) {
        Window window = windows.get(new Key(kind, normalizedEmail));
        return window == null || !window.expiresAt().isAfter(clock.instant()) ? 0 : window.count();
    }

    /** 테스트 사이에 비운다 — 컨텍스트를 같이 쓰는 다른 테스트에 번지지 않게. */
    public void clear() {
        windows.clear();
    }

    private void failIfUnavailable() {
        if (unavailable) {
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
    }
}
