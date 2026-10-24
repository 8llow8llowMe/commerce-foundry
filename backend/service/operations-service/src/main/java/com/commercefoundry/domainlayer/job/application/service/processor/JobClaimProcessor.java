package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.info.ClaimableJobInfo;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워커의 작업 가져가기. 인스턴스가 여럿이어도 분산 락(ShedLock · {@code SKIP LOCKED})을 두지 않는다 — 후보를 읽은 뒤 건마다 다시 읽어 도메인
 * {@link Job#claim} 을 저장하고, 같은 작업을 둘이 가져가면 {@code @Version} 이 하나만 통과시킨다. 진 쪽은 {@code JOB_CONFLICT}(409)로 롤백되고
 * 워커는 "다른 워커가 가져감" 으로 보고 넘어간다.
 *
 * <p>트랜잭션은 메서드마다 건다 — 워커 Facade 는 한 번의 폴링에서 후보 조회와 건별 가져가기를 각자 트랜잭션으로 부르고, 가져간 작업은 트랜잭션 밖
 * 실행 스레드로 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobClaimProcessor {

    private final JobRepositoryPort jobRepositoryPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** 지금 가져갈 수 있는 작업 후보 — 우선순위 내림차순 → 예약 시각 → 아이디 오름차순. 종류가 비었으면 조회하지 않는다. */
    @Transactional(readOnly = true)
    public List<ClaimableJobInfo> findClaimable(Collection<String> types, int limit) {
        if (types.isEmpty() || limit <= 0) {
            return List.of();
        }
        return jobRepositoryPort.findClaimable(types, clock.instant(), limit).stream()
            .map(candidate -> new ClaimableJobInfo(candidate.jobId(), candidate.type()))
            .toList();
    }

    /**
     * 작업 한 건을 가져간다. 다시 읽어 아직 가져갈 수 있을 때만 바꾼다 — 그 사이 다른 워커가 가져갔거나 취소됐으면 빈 값이다.
     * lease 가 만료된 작업을 회수했는데 시도 상한이거나 취소 요청을 받은 작업이면 끝내고(FAILED · CANCELLED) 종료 이벤트를 낸 뒤 빈 값이다.
     *
     * @return RUNNING 이 된 작업 (이 워커 · 새 시도 번호). 실행할 것이 없으면 빈 값
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code JOB_CONFLICT} — 같은 작업을 다른 워커가 먼저 가져갔다(롤백됨)
     */
    @Transactional
    public Optional<Job> claim(long jobId, String workerId, Duration lease) {
        Instant now = clock.instant();
        Optional<Job> found = jobRepositoryPort.findById(jobId);
        if (found.isEmpty() || !found.get().isClaimable(now)) {
            return Optional.empty();
        }
        Job claimed = jobRepositoryPort.update(found.get().claim(workerId, now, lease));
        if (claimed.status() != JobStatus.RUNNING) {
            log.warn("job reclaim finished expired job. jobId={}, type={}, attempt={}, status={}, errorCode={}",
                claimed.id(), claimed.type(), claimed.attempt(), claimed.status(), claimed.errorCode());
            eventPublisher.publishEvent(JobFinishedEvent.of(claimed));
            return Optional.empty();
        }
        return Optional.of(claimed);
    }
}
