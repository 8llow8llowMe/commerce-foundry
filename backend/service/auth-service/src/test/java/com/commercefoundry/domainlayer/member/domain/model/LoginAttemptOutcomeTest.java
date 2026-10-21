package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginAttemptOutcomeTest {

    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    @Test
    @DisplayName("허용 횟수 번째 시도까지는 넘기지 않고, 그다음 시도부터 넘긴다 (count > max)")
    void exceedsAfterMaxAttempts() {
        assertThat(new LoginAttemptOutcome(1, RETRY_AFTER).exceeds(5)).isFalse();
        assertThat(new LoginAttemptOutcome(5, RETRY_AFTER).exceeds(5)).isFalse();
        assertThat(new LoginAttemptOutcome(6, RETRY_AFTER).exceeds(5)).isTrue();
        assertThat(new LoginAttemptOutcome(1, RETRY_AFTER).exceeds(0)).isTrue();
    }

    @Test
    @DisplayName("남은 시간 0 은 받는다 — 창이 끝나는 바로 그 순간이다")
    void zeroRetryAfterAccepted() {
        assertThat(new LoginAttemptOutcome(1, Duration.ZERO).retryAfter()).isZero();
    }

    @Test
    @DisplayName("시도 수가 1 미만이거나 남은 시간이 음수 · null 이면 받지 않는다")
    void rejected() {
        assertThatThrownBy(() -> new LoginAttemptOutcome(0, RETRY_AFTER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptOutcome(1, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptOutcome(1, null)).isInstanceOf(NullPointerException.class);
    }
}
