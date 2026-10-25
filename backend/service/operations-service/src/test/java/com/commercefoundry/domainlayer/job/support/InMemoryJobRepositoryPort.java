package com.commercefoundry.domainlayer.job.support;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.query.ClaimableJobQueryResult;
import com.commercefoundry.domainlayer.job.application.port.out.query.JobSummaryQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Processor · Facade 단위 테스트용 가짜 저장소. 실제 어댑터와 같은 계약을 흉내 낸다 — INSERT 는 활성 멱등 키 유일성을, merge 는 버전 비교를,
 * 조건부 UPDATE 는 조건과 버전 올림을 지킨다. 실제 질의는 {@code JobPersistenceAdapterTest}(PostgreSQL)가 본다.
 */
public class InMemoryJobRepositoryPort implements JobRepositoryPort {

    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Long, Instant> requestedAt = new ConcurrentHashMap<>();

    /** 다음 {@link #insert} 를 멱등 키 충돌로 실패시킨다 — 동시 등록에서 진 쪽을 흉내 낸다. 실패 전에 {@code winner} 를 저장한다. */
    private Job pendingDedupWinner;

    public void loseNextInsertTo(Job winner) {
        this.pendingDedupWinner = winner;
    }

    /** 다른 워커 · 요청이 그 사이 바꾼 것처럼 저장된 작업을 덮는다(버전 +1). */
    public void overwrite(Job job) {
        Job stored = jobs.get(job.id());
        jobs.put(job.id(), withVersion(job, stored.version() + 1));
    }

    public Job get(long jobId) {
        return jobs.get(jobId);
    }

    @Override
    public Optional<Job> findById(long jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /** 어댑터와 같은 순서 — 아이디 내림차순. */
    @Override
    public Page<JobSummaryQueryResult> findSummaryPage(JobStatus status, String type, int page, int size) {
        List<Job> matched = jobs.values().stream()
            .filter(job -> status == null || job.status() == status)
            .filter(job -> type == null || type.equals(job.type()))
            .sorted(Comparator.comparingLong(Job::id).reversed())
            .toList();
        List<JobSummaryQueryResult> content = matched.stream().skip((long) page * size).limit(size).map(this::toSummary).toList();
        return new PageImpl<>(content, PageRequest.of(page, size), matched.size());
    }

    @Override
    public Optional<JobSummaryQueryResult> findSummary(long jobId) {
        return findById(jobId).map(this::toSummary);
    }

    /** 요청 시각은 넣은 때의 예약 시각으로 흉내 낸다(실제 어댑터는 행의 생성 시각). */
    private JobSummaryQueryResult toSummary(Job job) {
        return new JobSummaryQueryResult(
            job.id(), job.type(), job.status(), job.progressPercent(), job.progressMessage(), job.cancelRequested(), job.errorCode(),
            requestedAt.get(job.id()), job.finishedAt()
        );
    }

    @Override
    public Optional<Job> findActiveByDedupKey(String type, String dedupKey) {
        return jobs.values().stream().filter(job -> type.equals(job.type()) && dedupKey.equals(job.activeDedupKey())).findFirst();
    }

    /** 어댑터와 같은 순서 — lease 만료 RUNNING(만료 시각 순)을 먼저, 남은 자리를 QUEUED(우선순위 내림차순 → 예약 시각 → 아이디)로 채운다. */
    @Override
    public List<ClaimableJobQueryResult> findClaimable(Collection<String> types, Instant now, int limit) {
        Stream<Job> expired = jobs.values().stream()
            .filter(job -> types.contains(job.type()) && job.status() == JobStatus.RUNNING && job.isClaimable(now))
            .sorted(Comparator.comparing(Job::leaseExpiresAt).thenComparingLong(Job::id));
        Stream<Job> queued = jobs.values().stream()
            .filter(job -> types.contains(job.type()) && job.status() == JobStatus.QUEUED && job.isClaimable(now))
            .sorted(Comparator.comparingInt(Job::priority).reversed().thenComparing(Job::scheduledAt).thenComparingLong(Job::id));
        return Stream.concat(expired, queued)
            .limit(limit)
            .map(job -> new ClaimableJobQueryResult(job.id(), job.type()))
            .toList();
    }

    @Override
    public synchronized Job insert(Job job) {
        if (pendingDedupWinner != null) {
            jobs.put(pendingDedupWinner.id(), withVersion(pendingDedupWinner, 0L));
            pendingDedupWinner = null;
            throw new JobException(JobErrorCode.JOB_DEDUP_CONFLICT);
        }
        if (job.activeDedupKey() != null && findActiveByDedupKey(job.type(), job.activeDedupKey()).isPresent()) {
            throw new JobException(JobErrorCode.JOB_DEDUP_CONFLICT);
        }
        Job saved = withVersion(job, 0L);
        jobs.put(job.id(), saved);
        requestedAt.put(job.id(), job.scheduledAt());
        return saved;
    }

    @Override
    public synchronized Job update(Job job) {
        Job stored = jobs.get(job.id());
        if (stored == null || !stored.version().equals(job.version())) {
            throw new JobException(JobErrorCode.JOB_CONFLICT);
        }
        Job saved = withVersion(job, job.version() + 1);
        jobs.put(job.id(), saved);
        return saved;
    }

    @Override
    public synchronized int heartbeat(
        long jobId, String workerId, int attempt, Integer progressPercent, String progressMessage, Instant leaseExpiresAt, Instant now
    ) {
        Job stored = jobs.get(jobId);
        if (stored == null || !stored.isOwnedBy(workerId, attempt) || stored.cancelRequested()) {
            return 0;
        }
        jobs.put(jobId, copy(stored, leaseExpiresAt, progressPercent, progressMessage, stored.version() + 1));
        return 1;
    }

    @Override
    public synchronized int requestCancel(long jobId, Long memberId, Instant now) {
        Job stored = jobs.get(jobId);
        if (stored == null || stored.status() != JobStatus.RUNNING || stored.cancelRequested()) {
            return 0;
        }
        jobs.put(jobId, withVersion(stored.requestCancel(memberId, now), stored.version() + 1));
        return 1;
    }

    private static Job withVersion(Job job, long version) {
        return copy(job, job.leaseExpiresAt(), job.progressPercent(), job.progressMessage(), version);
    }

    /** 도메인에 setter 가 없어 canonical 생성자로 저장소가 바꾸는 칸(lease · 진행률 · 버전)만 바꾼 사본을 만든다. */
    private static Job copy(Job job, Instant leaseExpiresAt, Integer progressPercent, String progressMessage, long version) {
        return new Job(
            job.id(), job.type(), job.status(), job.priority(), job.payloadJson(), job.dedupKey(), job.requestedByMemberId(), job.scheduledAt(),
            job.attempt(), job.maxAttempts(), job.workerId(), leaseExpiresAt, job.startedAt(), job.finishedAt(),
            progressPercent, progressMessage, job.cancelRequested(), job.cancelRequestedByMemberId(),
            job.resultJson(), job.errorCode(), job.errorMessage(), version
        );
    }
}
