package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderPropertiesTest {

    @Test
    @DisplayName("lease 가 빠지거나 0 · 음수면 기동하지 않는다")
    void rejects() {
        assertThatThrownBy(() -> new OrderProperties(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderProperties.Creation(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("양수 lease 는 받는다")
    void accepts() {
        assertThat(new OrderProperties(new OrderProperties.Creation(Duration.ofSeconds(60))).creation().processingLease()).isEqualTo(Duration.ofSeconds(60));
    }
}
