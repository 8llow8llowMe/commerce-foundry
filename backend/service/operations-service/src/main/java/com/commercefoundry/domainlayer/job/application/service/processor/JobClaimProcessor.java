package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.port.out.JobDispatchPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 신호를 받은 워커의 작업 가져가기 ({@code architecture-guide.md} §10). 인스턴스가 여럿이어도 분산 락을 두지 않는다 — 신호가 가리킨 작업을 다시 읽어
 * 도메인 {@link Job#claim} 을 저장하고, 같은 작업을 둘이 가져가면 {@code @Version} 이 하나만 통과시킨다. 진 쪽은 {@code JOB_CONFLICT}(409)로 롤백되고
 * 워커는 "다른 워커가 가져감" 으로 보고 신호를 버린다.
 *
 * <p>신호는 최소 한 번 온다 — 중복 · 늦게 온 신호는 시도 번호로 거른다. 신호의 시도 번호가 지금 QUEUED 작업의 시도 번호와 같을 때만 가져간다.
 * 트랜잭션은 이 메서드 하나다 — 가져간 작업은 트랜잭션 밖 실행 스레드로 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class JobClaimProcessor {

    private final JobRepositoryPort jobRepositoryPort;
    private final JobDispatchPort jobDispatchPort;
    private final Clock clock;

    /**
     * 신호가 가리킨 시도를 가져간다.
     *
     * @param attempt 신호에 실린 시도 번호 — QUEUED 작업이 지금까지 가져간 횟수
     * @param type 신호에 실린 종류 — 저장된 종류와 다르면 엉뚱한 신호라 버린다
     * @return 가져감(RUNNING 이 된 작업) · 아직 예약 시각 전 · 버릴 신호(없음 · 이미 가져감 · 끝남 · 다른 시도)
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code JOB_CONFLICT} — 같은 작업을 다른 워커가 먼저 가져갔다(롤백됨)
     */
    @Transactional
    public ClaimResult claim(long jobId, int attempt, String type, String workerId, Duration lease) {
        Instant now = clock.instant();
        Optional<Job> found = jobRepositoryPort.findById(jobId);
        if (found.isEmpty()) {
            return ClaimResult.stale();
        }
        Job job = found.get();
        if (job.status() != JobStatus.QUEUED || job.attempt() != attempt || !job.type().equals(type)) {
            return ClaimResult.stale();
        }
        if (!job.isClaimable(now)) {
            return ClaimResult.notDue(job.scheduledAt());
        }
        return ClaimResult.claimed(jobRepositoryPort.update(job.claim(workerId, now, lease)));
    }

    /**
     * 지금 실행할 수 없는 신호(종류별 칸이 참 · 예약 시각 전)를 같은 시도 번호로 다시 내 미룬다 — 받은 신호는 확인하고 버린다.
     * 컨슈머를 멈추는 nack 과 달리 이 인스턴스의 다른 파티션 · 다른 종류 신호를 막지 않는다.
     *
     * @param availableAt 다시 낸 신호가 나갈 시각
     * @return 다시 냈으면 true, 버릴 신호(없음 · 이미 가져감 · 끝남 · 다른 시도)면 false
     */
    @Transactional
    public boolean defer(long jobId, int attempt, String type, Instant availableAt) {
        Optional<Job> found = jobRepositoryPort.findById(jobId);
        if (found.isEmpty()) {
            return false;
        }
        Job job = found.get();
        if (job.status() != JobStatus.QUEUED || job.attempt() != attempt || !job.type().equals(type)) {
            return false;
        }
        jobDispatchPort.dispatch(job, availableAt);
        return true;
    }

    /**
     * 가져가기 결과.
     *
     * @param job 가져간 작업 (CLAIMED 만)
     * @param scheduledAt 예약 시각 (NOT_DUE 만)
     */
    public record ClaimResult(Outcome outcome, Job job, Instant scheduledAt) {

        static ClaimResult claimed(Job job) {
            return new ClaimResult(Outcome.CLAIMED, job, null);
        }

        static ClaimResult notDue(Instant scheduledAt) {
            return new ClaimResult(Outcome.NOT_DUE, null, scheduledAt);
        }

        static ClaimResult stale() {
            return new ClaimResult(Outcome.STALE, null, null);
        }

        public enum Outcome {
            /** RUNNING 으로 바꿨다 — 실행한다 */
            CLAIMED,
            /** 예약 시각 전이다 — 신호를 미룬다 */
            NOT_DUE,
            /** 버릴 신호다 — 작업이 없거나 이미 가져갔거나 끝났거나 다른 시도의 신호다 */
            STALE
        }
    }
}
