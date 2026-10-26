package com.commercefoundry.domainlayer.notification.application.model;

import java.io.IOException;

/**
 * 한 어드민에게 열린 알림 스트림. 구현은 진입 adapter 가 만든다 (#213).
 */
@FunctionalInterface
public interface NotificationStream {

    void send(NotificationPush message) throws IOException;
}
