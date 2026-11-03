package com.commercefoundry.domainlayer.notification.adapter.in.scheduler;

import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyRetentionSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("operations.notifications.anomaly.enabled")
public class AnomalyRetentionScheduler {
    private final AnomalyRetentionSchedulerUseCase useCase;

    @Scheduled(initialDelayString = "${operations.notifications.anomaly.cleanup-interval:1h}",
        fixedDelayString = "${operations.notifications.anomaly.cleanup-interval:1h}")
    public void purgeExpired() {
        useCase.purgeExpiredRecords();
    }
}
