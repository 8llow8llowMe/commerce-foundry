package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 어드민 알림 조회 · 읽음 · 스트림 등록 (#213). */
@Component
@RequiredArgsConstructor
public class NotificationAdminProcessor {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final NotificationPushPort notificationPushPort;

    public Page<Notification> list(long recipientMemberId, int page, int size) {
        return notificationRepositoryPort.findPage(recipientMemberId, page, size);
    }

    public long unreadCount(long recipientMemberId) {
        return notificationRepositoryPort.countUnread(recipientMemberId);
    }

    public Notification markRead(long notificationId, long recipientMemberId, Instant now) {
        Notification notification = notificationRepositoryPort.findById(notificationId)
            .filter(found -> found.recipientMemberId() == recipientMemberId)
            .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOT_FOUND));
        Notification read = notification.markRead(now);
        if (read == notification) {
            return notification;
        }
        return notificationRepositoryPort.save(read);
    }

    public int markAllRead(long recipientMemberId, Instant now) {
        return notificationRepositoryPort.markAllRead(recipientMemberId, now);
    }

    public void openStream(long recipientMemberId, NotificationStream stream) {
        notificationPushPort.register(recipientMemberId, stream);
    }

    public void closeStream(long recipientMemberId, NotificationStream stream) {
        notificationPushPort.unregister(recipientMemberId, stream);
    }
}
