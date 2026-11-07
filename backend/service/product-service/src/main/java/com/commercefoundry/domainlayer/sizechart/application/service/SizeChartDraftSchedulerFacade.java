package com.commercefoundry.domainlayer.sizechart.application.service;

import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftSchedulerUseCase;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftCleanupProcessor;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class SizeChartDraftSchedulerFacade implements SizeChartDraftSchedulerUseCase {
    private final SizeChartDraftCleanupProcessor processor;
    private final SizeChartDraftProperties properties;
    private final Clock clock;

    @Override
    public int deleteExpiredDrafts() {
        var cutoff = clock.instant();
        int limit = properties.getCleanupBatchSize();
        int total = 0;
        for (int batch = 0; batch < properties.getCleanupMaxBatchesPerRun(); batch++) {
            int deleted = processor.deleteExpiredBatch(cutoff, limit);
            total += deleted;
            if (deleted < limit) {
                break; // Includes another instance having already removed the selected rows.
            }
        }
        return total;
    }
}
