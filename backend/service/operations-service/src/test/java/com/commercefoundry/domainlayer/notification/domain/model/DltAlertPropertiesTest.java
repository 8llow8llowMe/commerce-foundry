package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.*;
import com.commercefoundry.global.properties.DltAlertProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DltAlertPropertiesTest {
    @Test
    void defaultSuppressionAndBounds() {
        assertThat(new DltAlertProperties(false, null).suppression()).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> new DltAlertProperties(true, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DltAlertProperties(true, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DltAlertProperties(true, Duration.ofDays(8))).isInstanceOf(IllegalArgumentException.class);
    }
}
