package com.commercefoundry.domainlayer.visitanalytics.adapter.in.scheduler;

import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VisitAnalyticsRetentionScheduler {

    private final VisitAnalyticsUseCase useCase;
    private final LockProvider locks;
    private final Clock clock;

    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    public void purgeExpiredData() {
        var lock = locks.lock(new LockConfiguration(clock.instant(), "operations-visit-analytics-retention",
            Duration.ofMinutes(30), Duration.ofMinutes(1)));
        if (lock.isEmpty()) {
            return;
        }
        try {
            useCase.purgeExpiredData();
        } finally {
            lock.get().unlock();
        }
    }
}
