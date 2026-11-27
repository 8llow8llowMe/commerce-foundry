package com.commercefoundry.domainlayer.visitanalytics.adapter.in.scheduler;

import com.commercefoundry.domainlayer.visitanalytics.application.service.VisitAnalyticsFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VisitAnalyticsRetentionScheduler {

    private final VisitAnalyticsFacade facade;

    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    public void purgeExpiredData() {
        facade.purgeExpiredData();
    }
}
