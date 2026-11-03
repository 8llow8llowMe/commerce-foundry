package com.commercefoundry.domainlayer.notification.application.port.in;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;

public interface AnomalyNotificationPushUseCase {
    void enqueue(List<Notification> notifications);
}
