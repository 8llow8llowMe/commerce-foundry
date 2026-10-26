package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 어드민 알림 센터 설정 (#213). 기본값은 {@code application.yml} 에 둔다 — 값이 빠지거나 0 · 음수면 기동하지 않는다.
 *
 * @param retention 보존 기간과 정리 주기
 * @param streamTimeout SSE 연결을 붙잡는 시간
 */
@ConfigurationProperties(prefix = "operations.notifications")
public record NotificationProperties(Retention retention, Duration streamTimeout) {

    public NotificationProperties {
        Objects.requireNonNull(retention, "operations.notifications.retention 설정이 없습니다.");
        Objects.requireNonNull(streamTimeout, "operations.notifications.stream-timeout 설정이 없습니다.");
        if (streamTimeout.isZero() || streamTimeout.isNegative()) {
            throw new IllegalArgumentException("operations.notifications.stream-timeout 은 양수여야 합니다.");
        }
    }

    public record Retention(Duration initialDelay, Duration fixedDelay, int days) {

        public Retention {
            Objects.requireNonNull(initialDelay, "operations.notifications.retention.initial-delay 설정이 없습니다.");
            Objects.requireNonNull(fixedDelay, "operations.notifications.retention.fixed-delay 설정이 없습니다.");
            if (initialDelay.isNegative() || fixedDelay.isZero() || fixedDelay.isNegative() || days < 1) {
                throw new IllegalArgumentException("operations.notifications.retention 값이 올바르지 않습니다.");
            }
        }
    }
}
