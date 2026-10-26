package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationPropertiesTest {

    @Test
    @DisplayName("스트림 주석 간격이 게이트웨이 response-timeout(10초) 이상이면 기동하지 않는다")
    void heartbeatMustStayUnderGatewayTimeout() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stream-heartbeat");
    }

    private static NotificationProperties properties(Duration heartbeat) {
        return new NotificationProperties(
            new NotificationProperties.Retention(Duration.ofMinutes(1), Duration.ofHours(1), 90),
            Duration.ofMinutes(5),
            heartbeat
        );
    }
}
