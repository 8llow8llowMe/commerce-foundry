package com.commercefoundry.support;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 컨텍스트 흐름 테스트용 시도 계수기. Lua 스크립트({@code LoginAttemptRedisScripts#RECORD})와 같은 고정 창을 메모리로 흉내 낸다 —
 * 첫 시도에만 창을 걸고, 이어지는 시도는 창을 늘리지 않으며, 남은 시간은 창 끝 − 지금이다({@code InMemoryLoginAttemptStoreTest} 가 고정한다).
 * Redis TTL 대신 주입된 {@link Clock} 으로 만료를 재현한다 — Redis 처럼 지금이 창 끝을 <b>지났을 때만</b> 새 창이다. 창 끝과 같은 시각은
 * 아직 같은 창이고 남은 시간은 0 이다. 흐름 테스트는 {@link AdjustableClock} 을 넣어 창을 넘긴다.
 * 실제 Lua · 키 · TTL 은 {@code LoginAttemptRedisAdapterTest}(Testcontainers)가 본다.
 *
 * <p>키는 해시하지 않고 종류 · 이메일로 쓴다 — 메모리 밖으로 나가지 않는다. 이메일은 어댑터와 같이 {@link Member#canonicalEmail} 을 적용해
 * 대소문자 · 앞뒤 공백만 다른 변형이 한 창에 모인다. 1ms 미만 창은 어댑터와 같이 거절한다.
 * {@link #unavailable} 을 켜면 모든 메서드가 저장소 장애({@code AUTH_013})를 던진다 — 어댑터가 Redis 오류를 바꾸는 모양 그대로다.
 */
public class InMemoryLoginAttemptStore implements LoginAttemptCounterPort {

    private record Key(AttemptKind kind, String canonicalEmail) {

        private static Key of(AttemptKind kind, String email) {
            return new Key(kind, kind == AttemptKind.SIGN_UP_IP ? email : Member.canonicalEmail(email));
        }
    }

    private record Window(long count, Instant expiresAt) {

        /** Redis 는 지금 &gt; 만료 시각일 때만 키를 지운다 — 같은 시각은 아직 살아 있다. */
        private boolean expiredAt(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    private final Map<Key, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public volatile boolean unavailable;

    public InMemoryLoginAttemptStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized LoginAttemptOutcome record(AttemptKind kind, String normalizedEmail, Duration window) {
        if (window.toMillis() < 1) {
            throw new IllegalArgumentException("window 는 1ms 이상이어야 한다");
        }
        failIfUnavailable();
        Instant now = clock.instant();
        Window updated = windows.compute(Key.of(kind, normalizedEmail), (key, current) -> current == null || current.expiredAt(now)
            ? new Window(1, now.plus(window))
            : new Window(current.count() + 1, current.expiresAt()));
        return new LoginAttemptOutcome(updated.count(), Duration.between(now, updated.expiresAt()));
    }

    @Override
    public void reset(AttemptKind kind, String normalizedEmail) {
        failIfUnavailable();
        windows.remove(Key.of(kind, normalizedEmail));
    }

    /** 이메일 원문 대신 8자 16진 꼬리표 — 어댑터처럼 같은 이메일이면 같은 값이다. 저장소 장애와 무관하다(저장소를 부르지 않는다). */
    @Override
    public String subjectTag(String normalizedEmail) {
        return "%08x".formatted(Member.canonicalEmail(normalizedEmail).hashCode());
    }

    /** 지금 창 안의 시도 수. 창이 없거나 지났으면 0. 창 끝과 같은 시각은 아직 창 안이다. */
    public long attempts(AttemptKind kind, String normalizedEmail) {
        Window window = windows.get(Key.of(kind, normalizedEmail));
        return window == null || window.expiredAt(clock.instant()) ? 0 : window.count();
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
