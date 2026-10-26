package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.item.NotificationAdminItem;
import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.response.NotificationUnreadCountResponse;
import com.commercefoundry.domainlayer.notification.adapter.in.admin.presenter.NotificationAdminPresenter;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.in.NotificationAdminUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.NotificationAdminProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 어드민 알림 API. 스트림 등록은 트랜잭션 밖이다 — 연결은 DB 상태가 아니다 (#213). */
@Service
@RequiredArgsConstructor
public class NotificationAdminFacade implements NotificationAdminUseCase {

    private final NotificationAdminProcessor notificationAdminProcessor;
    private final NotificationAdminPresenter notificationAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationAdminItem> list(long recipientMemberId, int page, int size) {
        return notificationAdminPresenter.toPage(notificationAdminProcessor.list(recipientMemberId, page, size));
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationUnreadCountResponse unreadCount(long recipientMemberId) {
        return new NotificationUnreadCountResponse(notificationAdminProcessor.unreadCount(recipientMemberId));
    }

    @Override
    @Transactional
    public NotificationAdminItem markRead(long notificationId, long recipientMemberId, Instant now) {
        return notificationAdminPresenter.toItem(notificationAdminProcessor.markRead(notificationId, recipientMemberId, now));
    }

    @Override
    @Transactional
    public NotificationUnreadCountResponse markAllRead(long recipientMemberId, Instant now) {
        notificationAdminProcessor.markAllRead(recipientMemberId, now);
        return new NotificationUnreadCountResponse(notificationAdminProcessor.unreadCount(recipientMemberId));
    }

    @Override
    public void openStream(long recipientMemberId, NotificationStream stream) {
        notificationAdminProcessor.openStream(recipientMemberId, stream);
    }

    @Override
    public void closeStream(long recipientMemberId, NotificationStream stream) {
        notificationAdminProcessor.closeStream(recipientMemberId, stream);
    }
}
