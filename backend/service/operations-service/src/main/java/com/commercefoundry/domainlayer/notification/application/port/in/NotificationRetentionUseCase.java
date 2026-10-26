package com.commercefoundry.domainlayer.notification.application.port.in;

/** 보존 기간이 지난 알림을 지운다 (#213). */
public interface NotificationRetentionUseCase {

    /** @return 지운 건수 */
    int purgeExpired();
}
