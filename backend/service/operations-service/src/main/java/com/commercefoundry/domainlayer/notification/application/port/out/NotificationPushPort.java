package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;

/** 지금 연결된 어드민 화면에 알림을 민다. 연결이 없으면 조용히 넘어간다 (#213). */
public interface NotificationPushPort {

    void register(long recipientMemberId, NotificationStream stream);

    void unregister(long recipientMemberId, NotificationStream stream);

    void push(Notification notification);
}
