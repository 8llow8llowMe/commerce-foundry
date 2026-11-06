package com.commercefoundry.domainlayer.notification.adapter.out.lock;

import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertLockPort;
import com.commercefoundry.global.properties.PaymentAnomalyAlertProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAnomalyAlertLockAdapter implements PaymentAnomalyAlertLockPort {
    private final LockProvider locks;
    private final Clock clock;
    private final PaymentAnomalyAlertProperties properties;

    @Override
    public void runLocked(Runnable action) {
        var lock = locks.lock(new LockConfiguration(clock.instant(), "operations-payment-anomaly-alert", properties.lockAtMost(), Duration.ZERO));
        if (lock.isEmpty()) {
            log.warn("payment_anomaly_collection_failed reason=lock");
            return;
        }
        try {
            action.run();
        } finally {
            lock.get().unlock();
        }
    }
}
