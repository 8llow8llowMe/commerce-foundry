package com.commercefoundry.domainlayer.notification.application.port.in;

import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.item.NotificationAdminItem;
import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.response.NotificationUnreadCountResponse;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Instant;

/**
 * 어드민 알림 조회 · 읽음 · 스트림 (#213).
 */
public interface NotificationAdminUseCase {

    PageResponse<NotificationAdminItem> list(long recipientMemberId, int page, int size);

    NotificationUnreadCountResponse unreadCount(long recipientMemberId);

    NotificationAdminItem markRead(long notificationId, long recipientMemberId, Instant now);

    NotificationUnreadCountResponse markAllRead(long recipientMemberId, Instant now);

    void openStream(long recipientMemberId, NotificationStream stream);

    void closeStream(long recipientMemberId, NotificationStream stream);
}
