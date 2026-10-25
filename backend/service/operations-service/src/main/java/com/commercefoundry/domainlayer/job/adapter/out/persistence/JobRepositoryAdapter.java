package com.commercefoundry.domainlayer.job.adapter.out.persistence;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobEntity;
import com.commercefoundry.domainlayer.job.adapter.out.persistence.repository.JobRepository;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.mapper.JobMapper;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.query.ClaimableJobQueryResult;
import com.commercefoundry.domainlayer.job.application.port.out.query.JobSummaryQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 쓰기는 모두 바로 flush 한다 — 유니크 키 위반 · 버전 충돌을 이 자리에서 드러내 도메인 409 로 바꾼다.
 * 커밋 시점까지 미루면 트랜잭션 밖에서 터져 {@code JobException} 으로 바꿀 수 없다.
 */
@Component
@RequiredArgsConstructor
public class JobRepositoryAdapter implements JobRepositoryPort {

    static final String ACTIVE_DEDUP_KEY_CONSTRAINT = "uk_job_type_active_dedup_key";

    private final JobRepository jobRepository;
    private final JobMapper jobMapper;

    @Override
    public Optional<Job> findById(long jobId) {
        return jobRepository.findById(jobId).map(jobMapper::toDomain);
    }

    /** 조건 조합마다 파생 쿼리 하나 — 종류 조건은 {@code idx_job_type_id} 를 그대로 탄다. 상태 조건은 상태 선두 인덱스로 거른 뒤 정렬한다(어드민 전용). */
    @Override
    public Page<JobSummaryQueryResult> findSummaryPage(JobStatus status, String type, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<JobEntity> jobs;
        if (status != null && type != null) {
            jobs = jobRepository.findAllByStatusAndTypeOrderByIdDesc(status, type, pageable);
        } else if (status != null) {
            jobs = jobRepository.findAllByStatusOrderByIdDesc(status, pageable);
        } else if (type != null) {
            jobs = jobRepository.findAllByTypeOrderByIdDesc(type, pageable);
        } else {
            jobs = jobRepository.findAllByOrderByIdDesc(pageable);
        }
        return jobs.map(JobRepositoryAdapter::toSummary);
    }

    @Override
    public Optional<JobSummaryQueryResult> findSummary(long jobId) {
        return jobRepository.findById(jobId).map(JobRepositoryAdapter::toSummary);
    }

    /** 요청 시각은 행의 생성 시각(감사 칸)이다 — 도메인 {@code Job} 에는 없다. */
    private static JobSummaryQueryResult toSummary(JobEntity entity) {
        return new JobSummaryQueryResult(
            entity.getId(), entity.getType(), entity.getStatus(), entity.getProgressPercent(), entity.getProgressMessage(),
            entity.isCancelRequested(), entity.getErrorCode(), entity.getCreatedAt(), entity.getFinishedAt()
        );
    }

    @Override
    public Optional<Job> findActiveByDedupKey(String type, String dedupKey) {
        return jobRepository.findByTypeAndActiveDedupKey(type, dedupKey).map(jobMapper::toDomain);
    }

    /** 만료 RUNNING(드물다 — 워커가 죽은 경우)을 먼저, 남은 자리를 QUEUED 로 채운다. 두 쿼리가 각자 인덱스 순서로 읽는다. */
    @Override
    public List<ClaimableJobQueryResult> findClaimable(Collection<String> types, Instant now, int limit) {
        if (types.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<ClaimableJobQueryResult> expired = jobRepository.findClaimableExpired(types, JobStatus.RUNNING, now, Limit.of(limit));
        if (expired.size() >= limit) {
            return expired;
        }
        List<ClaimableJobQueryResult> candidates = new ArrayList<>(expired);
        candidates.addAll(jobRepository.findClaimableQueued(types, JobStatus.QUEUED, now, Limit.of(limit - expired.size())));
        return candidates;
    }

    /**
     * 새 작업(버전 null → persist)을 INSERT 한다. 멱등 키 유니크 키 위반만 {@code JOB_DEDUP_CONFLICT} 로 바꾼다 — 제약 이름으로 가린다.
     * 그 밖의 위반(Snowflake 아이디 충돌 등)은 예상 밖이라 그대로 올린다.
     */
    @Override
    public Job insert(Job job) {
        if (job.version() != null) {
            throw new IllegalArgumentException("이미 저장한 작업은 INSERT 할 수 없습니다. jobId=" + job.id());
        }
        try {
            return jobMapper.toDomain(jobRepository.saveAndFlush(jobMapper.toEntity(job)));
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, ACTIVE_DEDUP_KEY_CONSTRAINT)) {
                throw new JobException(JobErrorCode.JOB_DEDUP_CONFLICT, exception);
            }
            throw exception;
        }
    }

    /**
     * 버전이 있는 작업을 merge 한다. 읽은 뒤 다른 트랜잭션이 커밋했으면(전이 · heartbeat · 취소 요청 모두 버전을 올린다) merge(버전 비교) 또는 flush 의
     * {@code UPDATE ... where version = ?} 가 0행이 되어 {@link ObjectOptimisticLockingFailureException} 이 난다.
     */
    @Override
    public Job update(Job job) {
        if (job.version() == null) {
            throw new IllegalArgumentException("저장하지 않은 작업은 갱신할 수 없습니다. jobId=" + job.id());
        }
        try {
            return jobMapper.toDomain(jobRepository.saveAndFlush(jobMapper.toEntity(job)));
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new JobException(JobErrorCode.JOB_CONFLICT, exception);
        }
    }

    @Override
    public int heartbeat(long jobId, String workerId, int attempt, Integer progressPercent, String progressMessage, Instant leaseExpiresAt, Instant now) {
        return jobRepository.heartbeat(jobId, JobStatus.RUNNING, workerId, attempt, progressPercent, progressMessage, leaseExpiresAt, now);
    }

    @Override
    public int requestCancel(long jobId, Long memberId, Instant now) {
        return jobRepository.requestCancel(jobId, JobStatus.RUNNING, memberId, now);
    }
}
