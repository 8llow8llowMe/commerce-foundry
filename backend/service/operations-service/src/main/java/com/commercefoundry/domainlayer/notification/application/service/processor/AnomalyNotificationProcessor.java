package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.application.model.AnomalyNotificationsRecorded;
import com.commercefoundry.domainlayer.notification.application.model.AnomalyRecipientSnapshot;
import com.commercefoundry.domainlayer.notification.application.port.out.AdminNotificationRecipientQueryPort;
import com.commercefoundry.domainlayer.notification.application.port.out.AnomalyRepositoryPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalyState;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AnomalyNotificationProcessor {
    private final AnomalyRepositoryPort anomalyRepositoryPort;
    private final AdminNotificationRecipientQueryPort recipientQueryPort;
    private final NotificationRepositoryPort notificationRepositoryPort;
    private final SnowflakeIdGenerator idGenerator;
    private final AnomalyNotificationProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public AnomalyRecipientSnapshot findRecipients() {
        return new AnomalyRecipientSnapshot(recipientQueryPort.findActiveAdmins().memberIds());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean alreadyProcessed(AnomalyRecordCommand command) {
        return anomalyRepositoryPort.alreadyProcessed(command);
    }

    /** 상태 CAS · seen identity · 개인 알림 전부 같은 새 TX. CAS 실패는 TX 밖에서만 재시도한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeoutString = "${operations.notifications.anomaly.transaction-timeout-seconds:10}")
    public void record(AnomalyRecordCommand command, AnomalyRecipientSnapshot recipients) {
        if (anomalyRepositoryPort.alreadyProcessed(command)) {
            return;
        }
        Instant now = clock.instant();
        AnomalyState state = anomalyRepositoryPort.findState(command.topic())
            .orElseGet(() -> new AnomalyState(idGenerator.generateId(), command.topic(), now, null, null));
        boolean notify = state.shouldNotify(now, properties.suppressionWindow());
        anomalyRepositoryPort.saveStateAndFlush(state.observe(now, notify));
        anomalyRepositoryPort.insertRecord(idGenerator.generateId(), command, now);
        if (notify) {
            long emissionId = idGenerator.generateId();
            List<Notification> notifications = new ArrayList<>();
            for (long recipientId : recipients.memberIds()) {
                notifications.add(notificationRepositoryPort.insert(Notification.create(idGenerator.generateId(), command.source().notification(recipientId, emissionId))));
            }
            events.publishEvent(new AnomalyNotificationsRecorded(notifications));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpiredRecords() {
        return anomalyRepositoryPort.deleteProcessedBefore(clock.instant().minus(properties.recordRetention()));
    }
}
