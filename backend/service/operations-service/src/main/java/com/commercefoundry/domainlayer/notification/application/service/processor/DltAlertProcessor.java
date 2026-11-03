package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;
import com.commercefoundry.domainlayer.notification.application.port.out.DltAlertStatePort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.global.properties.DltAlertProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class DltAlertProcessor {
    private final NotificationRecipientPort recipients;
    private final DltAlertStatePort states;
    private final NotificationRepositoryPort notifications;
    private final NotificationPushPort push;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final DltAlertProperties properties;

    public List<Long> recipients() {
        return recipients.findAllActiveAdminIds();
    }

    @Transactional(timeout = 15)
    public void record(DltAlertCommand command, List<Long> recipients, NotificationSeverity severity) {
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("Recipients required");
        }
        var state = states.lockTopic(command.topic());
        if (command.offset() <= states.checkpoint(command.topic(), command.partition())) {
            return;
        }
        var now = clock.instant();
        if (state.shouldNotify(now, properties.suppression())) {
            var saved = new java.util.ArrayList<Notification>();
            for (long recipient : recipients) {
                long id = ids.generateId();
                saved.add(notifications.insert(Notification.create(id, new NotificationRequest(NotificationKind.ANOMALY, severity,
                    severity == NotificationSeverity.CRITICAL ? "결제 처리에 확인이 필요합니다. 담당자는 즉시 점검해 주세요." : "자동 처리에 확인이 필요합니다. 담당자는 점검해 주세요.",
                    severity == NotificationSeverity.CRITICAL ? "/admin/payments"
                        : command.topic().endsWith(".operations.job-run.dlt") ? "/admin/jobs" : null, recipient, "DLT_ALERT", id))));
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (var notification : saved) {
                        try {
                            push.push(notification);
                        } catch (RuntimeException ignored) {
                            log.warn("DLT notification push unavailable; persisted notification remains available");
                        }
                    }
                }
            });
            state = state.notified(now);
        }
        states.advance(command.topic(), command.partition(), command.offset(), state, ids.generateId());
    }
}
