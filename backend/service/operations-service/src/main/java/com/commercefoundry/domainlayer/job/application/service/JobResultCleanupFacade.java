package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.application.port.in.JobResultCleanupUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultDeleteProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultFileProcessor;
import com.commercefoundry.global.properties.JobResultFileProperties;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** TxC→외부 삭제→TxD. 한 행의 실패가 다음 행을 막지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobResultCleanupFacade implements JobResultCleanupUseCase {
    private final JobResultFileProcessor files;
    private final JobResultDeleteProcessor deletion;
    private final JobResultFileProperties properties;
    private final Clock clock;

    @Override
    public void cleanup() {
        if (!properties.cleanupEnabled() || !deletion.enabled()) { return; }
        Instant cutoff = clock.instant();
        Instant afterTime = Instant.EPOCH;
        long afterId = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            var candidates = files.findDue(cutoff, afterTime, afterId, properties.batchSize());
            for (var candidate : candidates) {
                try {
                    var retired = files.retire(candidate.id());
                    if (retired.isEmpty()) { continue; }
                    boolean success = false;
                    try {
                        deletion.delete(retired.get().objectKey());
                        success = true;
                    } catch (RuntimeException exception) {
                        log.warn("job result delete failed. fileId={}", candidate.id());
                    }
                    files.recordDeletion(candidate.id(), success);
                } catch (RuntimeException exception) {
                    log.warn("job result cleanup deferred. fileId={}", candidate.id());
                }
            }
            if (candidates.size() < properties.batchSize()) { return; }
            var last = candidates.getLast();
            afterTime = last.nextDeleteAt();
            afterId = last.id();
        }
    }
}
