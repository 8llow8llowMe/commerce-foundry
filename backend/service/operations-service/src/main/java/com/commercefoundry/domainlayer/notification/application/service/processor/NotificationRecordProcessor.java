package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 알림을 남기고, 커밋 뒤에 연결된 화면으로 민다. 다른 컨텍스트 Facade 도 {@link #record} 만 부른다 (#213).
 */
@Component
@RequiredArgsConstructor
public class NotificationRecordProcessor {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final NotificationPushPort notificationPushPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public void record(NotificationRequest request) {
        if (notificationRepositoryPort.findBySource(request.sourceType(), request.sourceId(), request.kind()).isPresent()) {
            return;
        }
        notificationRepositoryPort.save(Notification.create(snowflakeIdGenerator.generateId(), request));
    }

    public void push(String sourceType, long sourceId, NotificationKind kind) {
        notificationRepositoryPort.findBySource(sourceType, sourceId, kind)
            .ifPresent(notification -> notificationPushPort.push(notification.recipientMemberId(), NotificationPush.from(notification)));
    }
}
