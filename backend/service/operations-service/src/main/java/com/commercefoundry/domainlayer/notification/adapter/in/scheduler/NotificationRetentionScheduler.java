package com.commercefoundry.domainlayer.notification.adapter.in.scheduler;

import com.commercefoundry.domainlayer.notification.application.port.in.NotificationRetentionUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 보존 기간이 지난 알림을 지운다 (#213). 인스턴스가 여럿이면 같은 행을 같이 지워도 한 번만 없어진다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetentionScheduler {

    private final NotificationRetentionUseCase notificationRetentionUseCase;

    @Scheduled(
        initialDelayString = "${operations.notifications.retention.initial-delay}",
        fixedDelayString = "${operations.notifications.retention.fixed-delay}"
    )
    public void purgeExpired() {
        int deleted = notificationRetentionUseCase.purgeExpired();
        if (deleted > 0) {
            log.info("알림 보존 정리가 {}건을 지웠습니다.", deleted);
        }
    }
}
