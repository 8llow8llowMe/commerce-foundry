package com.commercefoundry.domainlayer.job.adapter.in.scheduler;

import com.commercefoundry.domainlayer.job.application.port.in.JobResultCleanupUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobResultCleanupScheduler {
    private final JobResultCleanupUseCase useCase;

    @Scheduled(initialDelayString = "${operations.jobs.result-file.cleanup-initial-delay:1m}",
        fixedDelayString = "${operations.jobs.result-file.cleanup-delay:1m}")
    public void cleanup() {
        try {
            useCase.cleanup();
        } catch (RuntimeException exception) {
            log.warn("job result cleanup unavailable; next sweep retries");
        }
    }
}
