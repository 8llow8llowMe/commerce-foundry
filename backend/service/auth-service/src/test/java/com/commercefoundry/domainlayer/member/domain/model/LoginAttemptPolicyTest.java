package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoginAttemptPolicyTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 100})
    @DisplayName("허용 횟수는 1 이상 100 이하면 받는다")
    void maxAttemptsAccepted(int maxAttempts) {
        assertThat(new LoginAttemptPolicy(maxAttempts, WINDOW).maxAttempts()).isEqualTo(maxAttempts);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    @DisplayName("허용 횟수가 범위 밖이면 받지 않는다")
    void maxAttemptsRejected(int maxAttempts) {
        assertThatThrownBy(() -> new LoginAttemptPolicy(maxAttempts, WINDOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT1M", "PT15M", "PT1H"})
    @DisplayName("창은 1분 이상 1시간 이하면 받는다")
    void windowAccepted(String window) {
        assertThat(new LoginAttemptPolicy(5, Duration.parse(window)).window()).isEqualTo(Duration.parse(window));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT59.999S", "PT0S", "PT-1M", "PT1H0.001S", "PT24H"})
    @DisplayName("창이 범위 밖이면 받지 않는다 — 상한은 1시간이다(키가 창만큼 남는다)")
    void windowRejected(String window) {
        assertThatThrownBy(() -> new LoginAttemptPolicy(5, Duration.parse(window))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("창이 null 이면 받지 않는다")
    void nullWindowRejected() {
        assertThatThrownBy(() -> new LoginAttemptPolicy(5, null)).isInstanceOf(NullPointerException.class);
    }
}
