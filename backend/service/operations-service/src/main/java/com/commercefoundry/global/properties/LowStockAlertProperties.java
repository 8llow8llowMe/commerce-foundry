package com.commercefoundry.global.properties;

import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 품절 임박 정기 점검(#229) 설정. 닫힌 기본값이며 값이 빠지면 아래 기본값을 쓰고, 범위를 벗어나면 기동하지 않는다.
 * 판정 값(페이지 수 · 해소 판정 · 재알림 · 점검 상한)의 정본 검증은 {@link LowStockAlertPolicy.Settings} 한 곳이다.
 */
@ConfigurationProperties("operations.notifications.low-stock-alert")
public record LowStockAlertProperties(boolean enabled, Duration initialDelay, Duration fixedDelay, Integer maxPages, Duration resolveAfter,
                                      Duration reminderInterval, Duration lockAtMost) {
    public LowStockAlertProperties {
        var defaults = LowStockAlertPolicy.Settings.defaults();
        initialDelay = bounded(initialDelay, Duration.ofMinutes(2), Duration.ofDays(1));
        fixedDelay = bounded(fixedDelay, Duration.ofMinutes(10), Duration.ofDays(1));
        maxPages = maxPages == null ? defaults.maxPages() : maxPages;
        if (maxPages < 1 || maxPages > LowStockAlertPolicy.MAX_PAGES_LIMIT) {
            throw new IllegalArgumentException("품절 임박 점검 페이지 수가 허용 범위를 벗어났습니다.");
        }
        resolveAfter = bounded(resolveAfter, defaults.resolveAfter(), Duration.ofDays(30));
        reminderInterval = bounded(reminderInterval, defaults.reminderInterval(), Duration.ofDays(365));
        lockAtMost = bounded(lockAtMost, defaults.lockAtMost(), Duration.ofMinutes(30));
        new LowStockAlertPolicy.Settings(maxPages, resolveAfter, reminderInterval, lockAtMost);
    }

    public LowStockAlertPolicy.Settings settings() {
        return new LowStockAlertPolicy.Settings(maxPages, resolveAfter, reminderInterval, lockAtMost);
    }

    private static Duration bounded(Duration value, Duration defaultValue, Duration maximum) {
        value = value == null ? defaultValue : value;
        if (value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("품절 임박 점검 기간이 허용 범위를 벗어났습니다.");
        }
        return value;
    }
}
