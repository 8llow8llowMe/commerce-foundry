package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnBooleanProperty("operations.notifications.anomaly.enabled")
public class AnomalyNotificationPushProcessor {
    private final NotificationPushPort pushPort;
    private final Executor executor;

    public AnomalyNotificationPushProcessor(NotificationPushPort pushPort, @Qualifier("anomalyNotificationPushExecutor") Executor executor) {
        this.pushPort = pushPort;
        this.executor = executor;
    }

    public void enqueue(List<Notification> notifications) {
        List<Notification> snapshot = List.copyOf(notifications);
        try {
            executor.execute(() -> {
                for (Notification notification : snapshot) {
                    try {
                        pushPort.push(notification);
                    } catch (RuntimeException exception) {
                        log.warn("anomaly notification push failed. causeType={}", exception.getClass().getSimpleName());
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            // 전송 큐 포화 / 종료에는 저장한 목록으로 따라잡는다. Kafka 처리 성공은 바뀌지 않는다.
            log.warn("anomaly notification push queue unavailable; use notification list catchup");
        }
    }
}
