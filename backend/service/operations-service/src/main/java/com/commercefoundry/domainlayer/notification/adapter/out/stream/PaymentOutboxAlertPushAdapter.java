package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxAlertPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentOutboxAlertPushAdapter implements PaymentOutboxAlertPushPort {
    private final NotificationPushPort push;
    private final Executor executor;

    public PaymentOutboxAlertPushAdapter(NotificationPushPort push, @Qualifier("paymentOutboxAlertPushExecutor") Executor executor) {
        this.push = push;
        this.executor = executor;
    }
    public void enqueue(List<Notification> notifications) {
        var batch = List.copyOf(notifications);
        try {
            executor.execute(() -> {
                for (var notification : batch) {
                    try {
                        push.push(notification);
                    } catch (RuntimeException ignored) {
                        log.warn("Payment outbox notification push unavailable; persisted notification remains available");
                    }
                }
            });
        } catch (RuntimeException ignored) {
            log.warn("Payment outbox notification push queue unavailable; persisted notification remains available");
        }
    }
}
