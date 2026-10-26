package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.port.in.NotificationRetentionUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.NotificationRetentionProcessor;
import com.commercefoundry.global.properties.NotificationProperties;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationRetentionFacade implements NotificationRetentionUseCase {

    private final NotificationRetentionProcessor notificationRetentionProcessor;
    private final NotificationProperties notificationProperties;

    @Override
    @Transactional
    public int purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(notificationProperties.retention().days()));
        return notificationRetentionProcessor.purge(cutoff);
    }
}
