package com.commercefoundry.domainlayer.aiusage.application.service;

import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMaintenanceUseCase;
import com.commercefoundry.domainlayer.aiusage.application.service.processor.AiUsageMaintenanceProcessor;
import com.commercefoundry.global.properties.AiUsageProperties;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiUsageMaintenanceFacade implements AiUsageMaintenanceUseCase {

    private final AiUsageMaintenanceProcessor aiUsageMaintenanceProcessor;
    private final AiUsageProperties aiUsageProperties;
    private final Clock clock;

    /** 한 배치의 호출 닫기와 월 카운터 이동이 한 트랜잭션이다. */
    @Override
    @Transactional
    public int abandonStale() {
        return aiUsageMaintenanceProcessor.abandonStale(clock.instant());
    }

    /** 트랜잭션은 Processor 의 배치마다다 — 배치가 가득 차면 상한까지 이어 간다. */
    @Override
    public int deleteExpired() {
        Instant now = clock.instant();
        AiUsageProperties.Retention retention = aiUsageProperties.retention();
        int deleted = 0;
        for (int batch = 0; batch < retention.maxBatchesPerRun(); batch++) {
            int count = aiUsageMaintenanceProcessor.deleteExpiredBatch(now);
            deleted += count;
            if (count < retention.batchSize()) {
                break;
            }
        }
        return deleted;
    }
}
