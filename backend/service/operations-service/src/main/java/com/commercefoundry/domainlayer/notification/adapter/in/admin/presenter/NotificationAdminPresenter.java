package com.commercefoundry.domainlayer.notification.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.item.NotificationAdminItem;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class NotificationAdminPresenter {

    public PageResponse<NotificationAdminItem> toPage(Page<Notification> notifications) {
        return PageResponse.of(notifications).map(this::toItem);
    }

    public NotificationAdminItem toItem(Notification notification) {
        return NotificationAdminItem.builder()
            .notificationId(String.valueOf(notification.id()))
            .kind(notification.kind().toMetadata())
            .severity(notification.severity().toMetadata())
            .title(notification.title())
            .linkPath(notification.linkPath())
            .read(!notification.unread())
            .createdAt(notification.createdAt())
            .build();
    }
}
