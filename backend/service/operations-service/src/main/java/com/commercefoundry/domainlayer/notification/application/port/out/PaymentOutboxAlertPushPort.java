package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;

public interface PaymentOutboxAlertPushPort {
    void enqueue(List<Notification> notifications);
}
