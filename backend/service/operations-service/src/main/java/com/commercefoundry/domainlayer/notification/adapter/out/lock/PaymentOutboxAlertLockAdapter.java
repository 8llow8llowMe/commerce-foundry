package com.commercefoundry.domainlayer.notification.adapter.out.lock;

import com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxAlertLockPort;
import com.commercefoundry.global.properties.PaymentOutboxAlertProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentOutboxAlertLockAdapter implements PaymentOutboxAlertLockPort {
    private final LockProvider locks;
    private final Clock clock;
    private final PaymentOutboxAlertProperties properties;

    public void runLocked(Runnable action) {
        var lock = locks.lock(new LockConfiguration(clock.instant(), "operations-payment-outbox-alert", properties.lockAtMost(), Duration.ZERO));
        if (lock.isPresent()) {
            try {
                action.run();
            } finally {
                lock.get().unlock();
            }
        }
    }
}
