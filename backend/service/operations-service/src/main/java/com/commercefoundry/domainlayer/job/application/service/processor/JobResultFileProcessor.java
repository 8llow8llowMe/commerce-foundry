package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultFileRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobResultContent;
import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.global.properties.JobResultFileProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JobResultFileProcessor {
    private final JobRepositoryPort jobs;
    private final JobResultFileRepositoryPort files;
    private final SnowflakeIdGenerator ids;
    private final JobResultFileProperties properties;
    private final Clock clock;

    @Transactional
    public JobResultFile reserve(long jobId, String workerId, int attempt, JobResultContent content) {
        Instant now = clock.instant();
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobException(JobErrorCode.NOT_FOUND_JOB));
        if (!owns(job, workerId, attempt, now)) {
            throw new JobException(JobErrorCode.JOB_CONFLICT);
        }
        if (content.bytes().length > properties.maxBytes()) {
            throw new JobExecutionException("RESULT_FILE_TOO_LARGE", false);
        }
        Instant expires = now.plus(content.sensitive() ? properties.sensitiveRetention() : properties.retention());
        Instant deadline = now.plus(properties.stagingTimeout());
        if (deadline.isAfter(expires)) { deadline = expires; }
        return files.insert(new JobResultFile(ids.generateId(), jobId, attempt, workerId,
            "job-results/" + jobId + "/" + attempt + "/" + UUID.randomUUID(), content.fileName(), content.contentType(), content.bytes().length,
            null, content.sensitive(), JobResultFile.State.STAGED, expires, deadline, deadline, 0, null, null));
    }

    @Transactional
    public void confirm(long fileId, Job job, String eTag) {
        Instant now = clock.instant();
        JobResultFile file = files.findById(fileId).orElseThrow(() -> new JobException(JobErrorCode.JOB_CONFLICT));
        if (file.jobId() != job.id() || file.attempt() != job.attempt() || !file.workerId().equals(job.workerId())) {
            throw new JobException(JobErrorCode.JOB_CONFLICT);
        }
        try {
            files.update(file.available(eTag, now));
        } catch (IllegalStateException exception) {
            throw new JobException(JobErrorCode.JOB_CONFLICT);
        }
    }

    @Transactional(readOnly = true)
    public JobResultFile downloadable(long jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobException(JobErrorCode.NOT_FOUND_JOB));
        if (job.status() != JobStatus.SUCCEEDED) { throw new JobException(JobErrorCode.NOT_FOUND_RESULT_FILE); }
        JobResultFile file = files.findByJobIdAndAttempt(jobId, job.attempt()).orElseGet(() -> {
            if (files.findConfirmedByJobIdAndAttempt(jobId, job.attempt()).isPresent()) {
                throw new JobException(JobErrorCode.RESULT_FILE_EXPIRED);
            }
            throw new JobException(JobErrorCode.NOT_FOUND_RESULT_FILE);
        });
        if (!clock.instant().isBefore(file.expiresAt()) || file.state() == JobResultFile.State.RETIRED) {
            throw new JobException(JobErrorCode.RESULT_FILE_EXPIRED);
        }
        if (file.state() != JobResultFile.State.AVAILABLE) { throw new JobException(JobErrorCode.NOT_FOUND_RESULT_FILE); }
        return file;
    }

    @Transactional(readOnly = true)
    public List<JobResultFile> findDue(Instant now, Instant afterTime, long afterId, int limit) {
        return files.findDue(now, afterTime, afterId, limit);
    }

    @Transactional
    public Optional<JobResultFile> retire(long id) {
        Instant now = clock.instant();
        Optional<JobResultFile> found = files.findById(id);
        if (found.isEmpty() || !found.get().due(now)) { return Optional.empty(); }
        return Optional.of(files.update(found.get().retire(now)));
    }

    @Transactional
    public void recordDeletion(long id, boolean success) {
        Instant now = clock.instant();
        JobResultFile file = files.findById(id).orElseThrow(() -> new JobException(JobErrorCode.JOB_CONFLICT));
        long backoff = Math.min(3600, 30L << Math.min(file.deleteAttempts(), 7));
        Instant next = now.plus(success ? properties.deleteRepeat() : java.time.Duration.ofSeconds(backoff));
        files.update(file.deleted(success, now, next));
    }

    public static boolean owns(Job job, String workerId, int attempt, Instant now) {
        return job.isOwnedBy(workerId, attempt) && job.leaseExpiresAt() != null && now.isBefore(job.leaseExpiresAt());
    }
}
