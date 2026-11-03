package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyNotificationPushUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.AnomalyNotificationPushProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("operations.notifications.anomaly.enabled")
public class AnomalyNotificationPushFacade implements AnomalyNotificationPushUseCase {
    private final AnomalyNotificationPushProcessor processor;

    @Override
    public void enqueue(List<Notification> notifications) {
        processor.enqueue(notifications);
    }
}
