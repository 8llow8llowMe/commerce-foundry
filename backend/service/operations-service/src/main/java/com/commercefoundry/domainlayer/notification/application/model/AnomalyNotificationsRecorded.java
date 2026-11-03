package com.commercefoundry.domainlayer.notification.application.model;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;

/** INSERT 결과의 안전한 immutable snapshot. AFTER_COMMIT에서 다시 SELECT하지 않는다. */
public record AnomalyNotificationsRecorded(List<Notification> notifications) {
    public AnomalyNotificationsRecorded {
        notifications = List.copyOf(notifications);
    }

    @Override
    public String toString() {
        return "AnomalyNotificationsRecorded[redacted]";
    }
}
