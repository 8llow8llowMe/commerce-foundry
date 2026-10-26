package com.commercefoundry.domainlayer.notification.application.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.time.Instant;

/**
 * SSE {@code notification} 이벤트 본문. 목록 항목과 같은 칸이다 ({@code api-design-guide.md} §7-2).
 * 응답 봉투는 씌우지 않는다.
 */
public record NotificationPush(
    String notificationId,
    CodeNameDescriptionMetadata kind,
    CodeNameDescriptionMetadata severity,
    String title,
    String linkPath,
    boolean read,
    Instant createdAt
) {

    public static NotificationPush from(Notification notification) {
        return new NotificationPush(
            String.valueOf(notification.id()),
            notification.kind().toMetadata(),
            notification.severity().toMetadata(),
            notification.title(),
            notification.linkPath(),
            !notification.unread(),
            notification.createdAt()
        );
    }
}
