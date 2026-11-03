package com.commercefoundry.domainlayer.notification.adapter.in.event;

import com.commercefoundry.domainlayer.notification.application.model.AnomalyNotificationsRecorded;
import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyNotificationPushUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("operations.notifications.anomaly.enabled")
public class AnomalyNotificationCommittedListener {
    private final AnomalyNotificationPushUseCase useCase;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void push(AnomalyNotificationsRecorded event) {
        useCase.enqueue(event.notifications());
    }
}
