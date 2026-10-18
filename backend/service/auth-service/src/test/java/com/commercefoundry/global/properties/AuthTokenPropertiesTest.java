package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthTokenPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT30S", "PT2M"})
    @DisplayName("재사용 유예는 0 이상 2분 이하면 받는다")
    void accepted(String grace) {
        assertThat(new AuthTokenProperties(Duration.parse(grace), 3).refreshReuseGrace()).isEqualTo(Duration.parse(grace));
    }

    @Test
    @DisplayName("유예가 음수 · 2분 초과 · null 이면 기동하지 않는다")
    void graceRejected() {
        assertThatThrownBy(() -> new AuthTokenProperties(Duration.ofSeconds(-1), 3)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.token.refresh-reuse-grace");
        assertThatThrownBy(() -> new AuthTokenProperties(Duration.ofMinutes(2).plusMillis(1), 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthTokenProperties(null, 3)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 10})
    @DisplayName("형제 상한은 0 이상 10 이하면 받는다")
    void maxSiblingsAccepted(int maxSiblings) {
        assertThat(new AuthTokenProperties(Duration.ofSeconds(30), maxSiblings).refreshReuseGraceMaxSiblings()).isEqualTo(maxSiblings);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 11})
    @DisplayName("형제 상한이 범위 밖이면 기동하지 않는다")
    void maxSiblingsRejected(int maxSiblings) {
        assertThatThrownBy(() -> new AuthTokenProperties(Duration.ofSeconds(30), maxSiblings)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.token.refresh-reuse-grace-max-siblings");
    }
}
