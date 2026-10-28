package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.global.properties.OnboardingProperties.Guide;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OnboardingPropertiesTest {

    @Test
    @DisplayName("허용 목록이 비었거나 없으면 기동하지 않는다")
    void emptyGuidesFail() {
        assertThatThrownBy(() -> new OnboardingProperties(List.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("비어");
        assertThatThrownBy(() -> new OnboardingProperties(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Console", "1console", "-console", "console_v1", "console v1", ""})
    @DisplayName("key 가 ^[a-z][a-z0-9-]{0,63}$ 가 아니면 기동하지 않는다")
    void invalidKeyFails(String key) {
        assertThatThrownBy(() -> new OnboardingProperties(List.of(new Guide(key, 1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("key");
    }

    @Test
    @DisplayName("65자 key 는 기동하지 않고 64자는 된다")
    void keyLengthLimit() {
        assertThatThrownBy(() -> new OnboardingProperties(List.of(new Guide("a".repeat(65), 1)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OnboardingProperties(List.of(new Guide("a".repeat(64), 1))).guides()).hasSize(1);
    }

    @Test
    @DisplayName("같은 key 가 두 번 있으면 기동하지 않는다")
    void duplicateKeyFails() {
        assertThatThrownBy(() -> new OnboardingProperties(List.of(new Guide("console", 1), new Guide("console", 2))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("중복");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("version 이 1 보다 작으면 기동하지 않는다")
    void versionMustBePositive(int version) {
        assertThatThrownBy(() -> new OnboardingProperties(List.of(new Guide("console", version))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version");
    }

    @Test
    @DisplayName("설정 순서를 지키고 key 로 찾는다")
    void keepsOrderAndFinds() {
        OnboardingProperties properties = new OnboardingProperties(List.of(new Guide("stocks", 2), new Guide("console", 1)));

        assertThat(properties.guides()).extracting(Guide::key).containsExactly("stocks", "console");
        assertThat(properties.find("stocks")).contains(new Guide("stocks", 2));
        assertThat(properties.find("unknown")).isEmpty();
    }
}
