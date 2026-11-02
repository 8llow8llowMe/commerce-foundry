package com.commercefoundry.domainlayer.job.adapter.out.persistence;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobResultFileEntity;
import com.commercefoundry.domainlayer.job.adapter.out.persistence.repository.JobResultFileRepository;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultFileRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobResultFileRepositoryAdapter implements JobResultFileRepositoryPort {
    private final JobResultFileRepository repository;

    @Override
    public JobResultFile insert(JobResultFile file) {
        if (file.version() != null) { throw new IllegalArgumentException("작업 결과 파일 값이 올바르지 않습니다."); }
        return save(file);
    }

    @Override
    public JobResultFile update(JobResultFile file) {
        if (file.version() == null) { throw new IllegalArgumentException("작업 결과 파일 값이 올바르지 않습니다."); }
        return save(file);
    }

    private JobResultFile save(JobResultFile file) {
        try {
            return domain(repository.saveAndFlush(entity(file)));
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new JobException(JobErrorCode.JOB_CONFLICT);
        }
    }

    @Override
    public Optional<JobResultFile> findById(long id) {
        return repository.findById(id).map(JobResultFileRepositoryAdapter::domain);
    }

    @Override
    public Optional<JobResultFile> findByJobIdAndAttempt(long jobId, int attempt) {
        return repository.findByJobIdAndAttemptAndState(jobId, attempt, JobResultFile.State.AVAILABLE).map(JobResultFileRepositoryAdapter::domain);
    }

    @Override
    public Optional<JobResultFile> findConfirmedByJobIdAndAttempt(long jobId, int attempt) {
        return repository.findConfirmed(jobId, attempt, Limit.of(1)).map(JobResultFileRepositoryAdapter::domain);
    }

    @Override
    public List<JobResultFile> findDue(Instant now, Instant afterTime, long afterId, int limit) {
        return repository.findDue(now, afterTime, afterId, Limit.of(Math.min(100, limit)))
            .stream().map(JobResultFileRepositoryAdapter::domain).toList();
    }

    private static JobResultFileEntity entity(JobResultFile f) {
        return JobResultFileEntity.builder()
            .id(f.id())
            .jobId(f.jobId())
            .attempt(f.attempt())
            .workerId(f.workerId())
            .objectKey(f.objectKey())
            .fileName(f.fileName())
            .contentType(f.contentType())
            .sizeBytes(f.sizeBytes())
            .eTag(f.eTag())
            .sensitive(f.sensitive())
            .state(f.state())
            .expiresAt(f.expiresAt())
            .stagingDeadline(f.stagingDeadline())
            .nextDeleteAt(f.nextDeleteAt())
            .deleteAttempts(f.deleteAttempts())
            .lastDeletedAt(f.lastDeletedAt())
            .version(f.version())
            .build();
    }

    private static JobResultFile domain(JobResultFileEntity e) {
        return new JobResultFile(
            e.getId(), e.getJobId(), e.getAttempt(), e.getWorkerId(), e.getObjectKey(),
            e.getFileName(), e.getContentType(), e.getSizeBytes(),
            e.getETag(), e.isSensitive(), e.getState(), e.getExpiresAt(), e.getStagingDeadline(),
            e.getNextDeleteAt(), e.getDeleteAttempts(), e.getLastDeletedAt(), e.getVersion());
    }
}
