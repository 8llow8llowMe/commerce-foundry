package com.commercefoundry.domainlayer.sizechart.adapter.in.scheduler;

import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SizeChartDraftCleanupScheduler {
    private final SizeChartDraftSchedulerUseCase useCase;

    @Scheduled(initialDelayString = "${size-chart.draft.cleanup-initial-delay:1m}",
        fixedDelayString = "${size-chart.draft.cleanup-fixed-delay:1h}")
    public void deleteExpiredDrafts() {
        try {
            int deleted = useCase.deleteExpiredDrafts();
            if (deleted > 0) {
                log.info("Expired size chart drafts deleted. count={}", deleted);
            }
        } catch (RuntimeException exception) {
            // Never log database exception messages/causes: they can contain stored draft contents.
            log.error("Size chart draft cleanup failed. errorType={}", exception.getClass().getSimpleName());
        }
    }
}
