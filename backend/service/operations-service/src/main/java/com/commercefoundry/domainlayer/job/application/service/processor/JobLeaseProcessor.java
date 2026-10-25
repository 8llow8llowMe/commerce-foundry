package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.info.ExpiredJobLeaseInfo;
import com.commercefoundry.domainlayer.job.application.model.JobLeaseCursor;
import com.commercefoundry.domainlayer.job.application.port.out.JobDispatchPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.query.ExpiredJobLeaseQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * lease 만료 판정 ({@code architecture-guide.md} §10). 워커가 죽어 heartbeat 가 끊긴 RUNNING 을 회수한다 — 시도가 남았으면 QUEUED 로 돌리고 실행 신호를
 * 다시 내고, 시도 상한이면 FAILED, 취소 요청을 받았으면 CANCELLED 로 끝낸다.
 *
 * <p>분산 락을 두지 않는다 — 여러 인스턴스가 같은 건을 판정해도 다시 읽은 뒤 도메인 전이를 저장하고 {@code @Version} 이 하나만 통과시킨다.
 * 트랜잭션은 건마다 하나다 — 한 건의 충돌이 배치 전체를 되돌리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLeaseProcessor {

    private final JobRepositoryPort jobRepositoryPort;
    private final JobDispatchPort jobDispatchPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** lease 가 끝난 RUNNING 후보 — {@code after} 다음부터 오래 만료된 순서로 {@code limit} 건까지. */
    @Transactional(readOnly = true)
    public List<ExpiredJobLeaseInfo> findExpired(JobLeaseCursor after, int limit) {
        ExpiredJobLeaseQueryResult cursor = after == null ? null : new ExpiredJobLeaseQueryResult(after.jobId(), after.leaseExpiresAt());
        return jobRepositoryPort.findExpiredLeases(clock.instant(), cursor, limit).stream()
            .map(expired -> new ExpiredJobLeaseInfo(expired.jobId(), expired.leaseExpiresAt()))
            .toList();
    }

    /**
     * 한 건을 회수한다. 다시 읽어 아직 lease 가 끝난 RUNNING 일 때만 바꾼다 — 그 사이 워커가 heartbeat 로 연장했거나 끝냈으면 그대로 둔다.
     *
     * @return 회수했으면 true
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code JOB_CONFLICT} — 읽은 뒤 워커 · 다른 인스턴스가 먼저 바꿨다(롤백됨)
     */
    @Transactional
    public boolean expire(long jobId) {
        Instant now = clock.instant();
        Optional<Job> found = jobRepositoryPort.findById(jobId);
        if (found.isEmpty() || !found.get().isLeaseExpired(now)) {
            return false;
        }
        Job expired = jobRepositoryPort.update(found.get().expireLease(now));
        if (expired.status() == JobStatus.QUEUED) {
            jobDispatchPort.dispatch(expired);
        } else {
            eventPublisher.publishEvent(JobFinishedEvent.of(expired));
        }
        log.warn("job lease expired. jobId={}, type={}, attempt={}, status={}, errorCode={}",
            expired.id(), expired.type(), expired.attempt(), expired.status(), expired.errorCode());
        return true;
    }
}
