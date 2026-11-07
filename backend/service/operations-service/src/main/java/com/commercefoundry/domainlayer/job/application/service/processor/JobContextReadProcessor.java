package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads the job domain in the transaction coordinated by the calling facade. */
@Component
@RequiredArgsConstructor
public class JobContextReadProcessor {
    private final JobRepositoryPort repository;

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Optional<Job> findById(long jobId) {
        return repository.findById(jobId);
    }
}
