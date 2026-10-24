package com.commercefoundry.domainlayer.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    /** 기본값 — 30s × 4^(n−1), 상한 30m, jitter 0.2. */
    private static final RetryPolicy DEFAULT = new RetryPolicy(Duration.ofSeconds(30), 4, Duration.ofMinutes(30), 0.2);

    @ParameterizedTest(name = "attempt={0} → {1}")
    @CsvSource({"1, PT30S", "2, PT2M", "3, PT8M", "4, PT30M", "10, PT30M", "1000, PT30M"})
    @DisplayName("jitter 가운데(0.5)에서는 min(상한, 30s × 4^(n−1)) 이다 — 지수가 커져도 넘치지 않고 상한에 머문다")
    void exponentialWithCap(int attempt, Duration expected) {
        assertThat(DEFAULT.delayAfter(attempt, 0.5)).isEqualTo(expected);
    }

    @Test
    @DisplayName("jitter 양끝 — 0 이면 −20%, 1 에 가까우면 +20% 쪽이다 (상한에도 jitter 가 붙는다)")
    void jitterBounds() {
        assertThat(DEFAULT.delayAfter(1, 0.0)).isEqualTo(Duration.ofSeconds(24));
        assertThat(DEFAULT.delayAfter(1, Math.nextDown(1.0))).isEqualTo(Duration.ofSeconds(36));
        assertThat(DEFAULT.delayAfter(4, 0.0)).isEqualTo(Duration.ofMinutes(24));
        assertThat(DEFAULT.delayAfter(4, Math.nextDown(1.0))).isEqualTo(Duration.ofMinutes(36));
    }

    @RepeatedTest(50)
    @DisplayName("난수 jitter 는 늘 ±20% 안이다")
    void randomJitterStaysInRange() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            Duration center = DEFAULT.delayAfter(attempt, 0.5);
            Duration delay = DEFAULT.delayAfter(attempt);

            assertThat(delay).isBetween(Duration.ofMillis(Math.round(center.toMillis() * 0.8)), Duration.ofMillis(Math.round(center.toMillis() * 1.2)));
        }
    }

    @Test
    @DisplayName("jitter 0 이면 늘 같은 지연이다")
    void noJitter() {
        RetryPolicy policy = new RetryPolicy(Duration.ofMillis(50), 2, Duration.ofMillis(200), 0.0);

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMillis(50));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayAfter(5)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("잘못된 설정 · 인자는 거절한다")
    void rejectsInvalid() {
        assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, 4, Duration.ofMinutes(30), 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofMinutes(1), 4, Duration.ofSeconds(30), 0.2)).as("상한 < 기본").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(30), 0.5, Duration.ofMinutes(30), 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(30), 4, Duration.ofMinutes(30), 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DEFAULT.delayAfter(0, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DEFAULT.delayAfter(1, 1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
