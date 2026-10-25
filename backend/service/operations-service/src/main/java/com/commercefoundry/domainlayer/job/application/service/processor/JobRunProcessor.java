package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobDispatchPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.domain.model.JobTransitionException;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 중인 시도의 기록 — heartbeat, 종료(성공 · 실패 · 취소), 돌려놓기, 취소 요청. 메서드마다 짧은 트랜잭션이다 — 핸들러 실행은 트랜잭션 밖이고,
 * 워커 Facade 가 그 앞뒤로 이 메서드들을 부른다.
 *
 * <p>종료는 <b>다시 읽어 아직 이 워커의 이 시도인지</b>({@link Job#isOwnedBy}) 확인한 뒤에만 쓴다. lease 를 잃은 사이 만료 판정이 회수했거나
 * 돌려놓았으면, 늦게 끝난 실행의 결과를 버리고 warn 을 남긴다 — 한 시도의 결과가 다른 시도를 덮지 않는다.
 * 끝났으면 같은 트랜잭션 안에서 {@link JobFinishedEvent} 를 한 번 발행한다(#213 알림이 함께 커밋 · 롤백된다).
 * 다시 QUEUED 가 됐으면(재시도 · 돌려놓기) 같은 트랜잭션 안에서 다음 시도의 실행 신호를 낸다({@link JobDispatchPort}) — 신호는 예약 시각(백오프)까지 미뤄 나간다.
 *
 * <p>로그에는 아이디 · 종류 · 시도 · 오류 코드만 남긴다. payload · 결과 · 오류 메시지는 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobRunProcessor {

    private final JobRepositoryPort jobRepositoryPort;
    private final JobDispatchPort jobDispatchPort;
    private final JobProperties jobProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * lease 를 연장하고 진행률을 쓴다. 조건부 UPDATE 가 0행이면 다시 읽어 취소 요청인지 lease 상실인지 가린다.
     *
     * @return 계속 실행 · 취소 요청 받음 · lease 잃음
     */
    @Transactional
    public HeartbeatResult heartbeat(long jobId, String workerId, int attempt, Integer progressPercent, String progressMessage, Duration lease) {
        Instant now = clock.instant();
        if (jobRepositoryPort.heartbeat(jobId, workerId, attempt, progressPercent, progressMessage, now.plus(lease), now) == 1) {
            return HeartbeatResult.ALIVE;
        }
        Optional<Job> found = jobRepositoryPort.findById(jobId);
        if (found.isPresent() && found.get().isOwnedBy(workerId, attempt) && found.get().cancelRequested()) {
            return HeartbeatResult.CANCEL_REQUESTED;
        }
        return HeartbeatResult.LOST;
    }

    /**
     * 성공으로 끝낸다. 결과 JSON 이 상한({@code operations.jobs.result.max-json-bytes})을 넘으면 다시 시도해도 같아 FAILED({@link Job#RESULT_TOO_LARGE})다.
     *
     * @return 기록했으면 true, 이미 이 워커의 시도가 아니어서 결과를 버렸으면 false
     * @throws JobException {@code JOB_CONFLICT} — 읽은 뒤 취소 요청 등이 버전을 올렸다. 다시 부르면 새로 읽는다
     */
    @Transactional
    public boolean succeed(long jobId, String workerId, int attempt, String resultJson) {
        int maxJsonBytes = jobProperties.result().maxJsonBytes();
        if (resultJson != null && Job.payloadBytes(resultJson) > maxJsonBytes) {
            return finish(jobId, workerId, attempt, "succeed", job -> job.fail(
                Job.RESULT_TOO_LARGE, "결과 JSON 이 " + maxJsonBytes + " 바이트를 넘습니다.", false, clock.instant(), jobProperties.retry().toPolicy()
            ));
        }
        return finish(jobId, workerId, attempt, "succeed", job -> job.succeed(resultJson, clock.instant()));
    }

    /**
     * 실패를 기록한다 — 시도가 남았고 재시도할 수 있으면 백오프 뒤 QUEUED, 아니면 FAILED, 취소 요청을 받았으면 CANCELLED.
     *
     * @return 기록했으면 true, 결과를 버렸으면 false
     */
    @Transactional
    public boolean fail(long jobId, String workerId, int attempt, String errorCode, String message, boolean retryable) {
        return finish(jobId, workerId, attempt, "fail",
            job -> job.fail(errorCode, message, retryable, clock.instant(), jobProperties.retry().toPolicy()));
    }

    /** 취소 요청을 받은 핸들러가 멈췄다 — CANCELLED 로 끝낸다. */
    @Transactional
    public boolean cancelled(long jobId, String workerId, int attempt) {
        return finish(jobId, workerId, attempt, "cancel", job -> job.cancel(clock.instant()));
    }

    /** graceful shutdown 에서 끝나지 못한 시도를 QUEUED 로 돌려놓는다(시도 번호를 되돌린다). 취소 요청을 받았으면 CANCELLED 다. */
    @Transactional
    public boolean release(long jobId, String workerId, int attempt) {
        return finish(jobId, workerId, attempt, "release", job -> job.release(clock.instant()));
    }

    /**
     * 취소를 요청한다. QUEUED 는 바로 CANCELLED 로 끝내고(종료 이벤트), RUNNING 은 플래그만 세운다 — 워커가 다음 checkpoint · heartbeat 에서 멈춘다.
     * 이미 요청된 RUNNING 은 그대로다(멱등). 어드민 취소 API(#212 PR-C)가 부른다.
     *
     * @param memberId 요청한 어드민. 시스템 요청이면 null
     * @return 요청 뒤 작업
     * @throws JobException {@code NOT_FOUND_JOB}, {@code JOB_ALREADY_FINISHED} 끝난 작업, {@code JOB_CONFLICT} 그 사이 워커가 바꿨다
     */
    @Transactional
    public Job requestCancel(long jobId, Long memberId) {
        Instant now = clock.instant();
        Job job = jobRepositoryPort.findById(jobId).orElseThrow(() -> new JobException(JobErrorCode.NOT_FOUND_JOB));
        Job requested;
        try {
            requested = job.requestCancel(memberId, now);
        } catch (JobTransitionException exception) {
            throw new JobException(JobErrorCode.JOB_ALREADY_FINISHED, exception);
        }
        if (requested.status() == JobStatus.CANCELLED) {
            Job cancelled = jobRepositoryPort.update(requested);
            eventPublisher.publishEvent(JobFinishedEvent.of(cancelled));
            log.info("job cancelled. jobId={}, type={}, attempt={}", cancelled.id(), cancelled.type(), cancelled.attempt());
            return cancelled;
        }
        if (requested == job) {
            return job;
        }
        if (jobRepositoryPort.requestCancel(jobId, memberId, now) == 0) {
            // 읽은 뒤 워커가 끝냈다 — 다시 읽어 알린다
            throw new JobException(JobErrorCode.JOB_CONFLICT);
        }
        log.info("job cancel requested. jobId={}, type={}, attempt={}", job.id(), job.type(), job.attempt());
        // 조건부 UPDATE 가 버전을 올렸다 — 메모리의 사본은 옛 버전이라 다시 읽어 돌려준다(응답의 버전 · 갱신 시각이 저장된 값과 맞게)
        return jobRepositoryPort.findById(jobId).orElseThrow(() -> new JobException(JobErrorCode.NOT_FOUND_JOB));
    }

    private boolean finish(long jobId, String workerId, int attempt, String action, UnaryOperator<Job> transition) {
        Optional<Job> found = jobRepositoryPort.findById(jobId);
        if (found.isEmpty() || !found.get().isOwnedBy(workerId, attempt)) {
            log.warn("job result discarded, no longer owned by this attempt. jobId={}, attempt={}, action={}, currentStatus={}, currentAttempt={}",
                jobId, attempt, action, found.map(Job::status).orElse(null), found.map(Job::attempt).orElse(null));
            return false;
        }
        Job saved = jobRepositoryPort.update(transition.apply(found.get()));
        if (saved.status().isFinished()) {
            eventPublisher.publishEvent(JobFinishedEvent.of(saved));
        } else if (saved.status() == JobStatus.QUEUED) {
            jobDispatchPort.dispatch(saved);
        }
        log.info("job attempt recorded. jobId={}, type={}, attempt={}, action={}, status={}, errorCode={}",
            saved.id(), saved.type(), attempt, action, saved.status(), saved.errorCode());
        return true;
    }

    /** heartbeat 결과. */
    public enum HeartbeatResult {
        /** lease 를 연장했다 — 계속 실행한다 */
        ALIVE,
        /** 취소 요청을 받았다 — 다음 checkpoint 에서 멈춘다 */
        CANCEL_REQUESTED,
        /** 이 시도가 더 이상 주인이 아니다(lease 만료로 회수됨 · 끝남) — 결과를 버린다 */
        LOST
    }
}
