package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SizeChartDraftCleanupProcessor {
    private final SizeChartDraftRepositoryPort repository;

    // Each bounded batch commits separately; no transaction is held across the whole sweep.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        return repository.deleteExpiredBatch(cutoff, limit);
    }
}
