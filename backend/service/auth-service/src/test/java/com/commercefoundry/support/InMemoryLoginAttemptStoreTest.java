package com.commercefoundry.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 흐름 테스트용 스텁이 Lua({@code LoginAttemptRedisScripts#RECORD})와 같은 고정 창으로 세는지 고정한다 —
 * 같은 경우를 {@code LoginAttemptRedisAdapterTest}(Testcontainers)가 실제 Lua 로 본다.
 */
class InMemoryLoginAttemptStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String EMAIL = "member@example.com";

    private final SettableClock clock = new SettableClock(NOW);
    private final InMemoryLoginAttemptStore store = new InMemoryLoginAttemptStore(clock);

    @Test
    @DisplayName("첫 시도는 1 과 창 전체, 이어지는 시도는 창을 늘리지 않고 남은 시간이 줄어든다")
    void fixedWindowDoesNotExtend() {
        assertThat(store.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(1, WINDOW));

        clock.advance(Duration.ofMinutes(5));
        assertThat(store.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(2, Duration.ofMinutes(10)));

        clock.advance(Duration.ofMinutes(5));
        assertThat(store.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(3, Duration.ofMinutes(5)));
        assertThat(store.attempts(AttemptKind.LOGIN, EMAIL)).isEqualTo(3);
    }

    @Test
    @DisplayName("창 끝 1ms 전까지는 같은 창이고, 창 끝과 같은 시각부터는 새 창(1)이다 — 만료는 주입된 시계로 재현한다")
    void windowExpiresAtBoundary() {
        store.record(AttemptKind.LOGIN, EMAIL, WINDOW);

        clock.set(NOW.plus(WINDOW).minusMillis(1));
        assertThat(store.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(2, Duration.ofMillis(1)));

        clock.set(NOW.plus(WINDOW));
        assertThat(store.attempts(AttemptKind.LOGIN, EMAIL)).as("창이 지나면 센 값이 없다").isZero();
        assertThat(store.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(1, WINDOW));
    }

    @Test
    @DisplayName("reset 하면 다음 시도는 1 부터 새 창으로 센다")
    void resetStartsOver() {
        store.record(AttemptKind.LOGIN, EMAIL, WINDOW);
        store.record(AttemptKind.LOGIN, EMAIL, WINDOW);
        clock.advance(Duration.ofMinutes(3));

        store.reset(AttemptKind.LOGIN, EMAIL);

        assertThat(store.attempts(AttemptKind.LOGIN, EMAIL)).isZero();
        assertThat(store.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(1, WINDOW));
    }

    @Test
    @DisplayName("로그인 · 회원가입 · 다른 이메일은 창을 따로 쓴다")
    void kindsAndEmailsAreSeparate() {
        store.record(AttemptKind.LOGIN, EMAIL, WINDOW);
        store.record(AttemptKind.LOGIN, EMAIL, WINDOW);

        assertThat(store.record(AttemptKind.SIGN_UP, EMAIL, WINDOW).count()).isEqualTo(1);
        assertThat(store.record(AttemptKind.LOGIN, "other@example.com", WINDOW).count()).isEqualTo(1);

        store.reset(AttemptKind.SIGN_UP, EMAIL);
        assertThat(store.attempts(AttemptKind.LOGIN, EMAIL)).as("다른 종류를 reset 해도 남는다").isEqualTo(2);
    }

    @Test
    @DisplayName("unavailable 이면 기록 · reset 모두 AUTH_013 이다")
    void unavailable() {
        store.unavailable = true;

        assertThatThrownBy(() -> store.record(AttemptKind.LOGIN, EMAIL, WINDOW))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        assertThatThrownBy(() -> store.reset(AttemptKind.LOGIN, EMAIL)).isInstanceOf(MemberException.class);
    }

    /** 테스트가 정한 시각에 멈춘 시계. */
    private static final class SettableClock extends Clock {

        private Instant instant;

        private SettableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("UTC 하나만 쓴다");
        }
    }
}
