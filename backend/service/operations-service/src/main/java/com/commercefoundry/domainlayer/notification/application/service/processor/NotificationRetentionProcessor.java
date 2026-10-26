package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 보존 기간이 지난 알림을 지운다 (#213). */
@Component
@RequiredArgsConstructor
public class NotificationRetentionProcessor {

    private final NotificationRepositoryPort notificationRepositoryPort;

    public int purge(Instant cutoff) {
        return notificationRepositoryPort.deleteCreatedBefore(cutoff);
    }
}
