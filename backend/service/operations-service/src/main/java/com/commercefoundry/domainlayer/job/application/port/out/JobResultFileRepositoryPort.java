package com.commercefoundry.domainlayer.job.application.port.out;

import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobResultFileRepositoryPort {
    JobResultFile insert(JobResultFile file);
    JobResultFile update(JobResultFile file);
    Optional<JobResultFile> findById(long id);
    Optional<JobResultFile> findByJobIdAndAttempt(long jobId, int attempt);
    Optional<JobResultFile> findConfirmedByJobIdAndAttempt(long jobId, int attempt);
    List<JobResultFile> findDue(Instant now, Instant afterTime, long afterId, int limit);
}
