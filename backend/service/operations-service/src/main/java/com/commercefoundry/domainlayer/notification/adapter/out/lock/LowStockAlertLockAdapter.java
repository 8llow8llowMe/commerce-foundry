package com.commercefoundry.domainlayer.notification.adapter.out.lock;

import com.commercefoundry.domainlayer.notification.application.port.out.LowStockAlertLockPort;
import com.commercefoundry.global.properties.LowStockAlertProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Component;

/**
 * ShedLock {@code operations-low-stock-alert}. lease 만료가 직렬화를 보장하지 않으므로 저장 쪽(워터마크 NOWAIT · 관측 시각 증가 · 경과 상한)이
 * 겹친 점검을 다시 걸러낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowStockAlertLockAdapter implements LowStockAlertLockPort {
    static final String LOCK_NAME = "operations-low-stock-alert";
    private final LockProvider locks;
    private final Clock clock;
    private final LowStockAlertProperties properties;

    @Override
    public void runLocked(Runnable action) {
        var lock = locks.lock(new LockConfiguration(clock.instant(), LOCK_NAME, properties.lockAtMost(), Duration.ZERO));
        if (lock.isEmpty()) {
            log.info("low_stock_alert_skipped reason=lock_busy");
            return;
        }
        try {
            action.run();
        } finally {
            unlock(lock.get());
        }
    }

    /** 점검이 이미 끝난 뒤의 해제 실패는 점검 실패로 올리지 않는다 — lease 는 lock-at-most 뒤 저절로 풀린다. */
    private static void unlock(SimpleLock lock) {
        try {
            lock.unlock();
        } catch (RuntimeException ignored) {
            log.warn("low_stock_alert_unlock_failed");
        }
    }
}
