package com.commercefoundry.domainlayer.job.application.service.processor;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor.HeartbeatResult;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실행 중인 시도의 기록 — heartbeat 판정, 주인 확인 뒤 종료, 재시도 · 상한, 취소 요청. 종료(끝난 상태)일 때만 종료 이벤트가 한 번 나간다.
 */
class JobRunProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final String WORKER = "operations-service:host:1:aaaaaaaa";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private InMemoryJobRepositoryPort repository;
    private List<Object> events;
    private JobRunProcessor processor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryJobRepositoryPort();
        events = new ArrayList<>();
        processor = new JobRunProcessor(repository, properties(), events::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("heartbeat — 이 워커의 시도면 계속, 취소 요청을 받았으면 취소, 다른 시도가 됐으면 lease 상실이다")
    void heartbeatResults() {
        Job running = insertRunning(1L, 3);
        assertThat(processor.heartbeat(1L, WORKER, 1, 40, "집계 중", LEASE)).isEqualTo(HeartbeatResult.ALIVE);
        assertThat(repository.get(1L).progressPercent()).isEqualTo(40);
        assertThat(repository.get(1L).leaseExpiresAt()).isEqualTo(NOW.plus(LEASE));

        repository.requestCancel(running.id(), 7L, NOW);
        assertThat(processor.heartbeat(1L, WORKER, 1, 50, null, LEASE)).isEqualTo(HeartbeatResult.CANCEL_REQUESTED);

        insertRunning(2L, 3);
        assertThat(processor.heartbeat(2L, "other-worker", 1, null, null, LEASE)).isEqualTo(HeartbeatResult.LOST);
        assertThat(processor.heartbeat(2L, WORKER, 2, null, null, LEASE)).isEqualTo(HeartbeatResult.LOST);
    }

    @Test
    @DisplayName("성공 — 이 워커의 시도면 SUCCEEDED · 결과 저장 · 종료 이벤트 1회, 아니면 결과를 버리고 아무것도 바꾸지 않는다")
    void succeedOnlyWhenOwned() {
        insertRunning(1L, 3);
        assertThat(processor.succeed(1L, "other-worker", 1, "{\"rows\":1}")).isFalse();
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(events).isEmpty();

        assertThat(processor.succeed(1L, WORKER, 1, "{\"rows\":1}")).isTrue();
        Job saved = repository.get(1L);
        assertThat(saved.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(saved.resultJson()).isEqualTo("{\"rows\":1}");
        assertThat(events).singleElement().isInstanceOfSatisfying(JobFinishedEvent.class, event -> {
            assertThat(event.jobId()).isEqualTo(1L);
            assertThat(event.status()).isEqualTo(JobStatus.SUCCEEDED);
        });
    }

    @Test
    @DisplayName("결과 JSON 이 상한을 넘으면 다시 해도 같아 재시도 없이 FAILED(RESULT_TOO_LARGE) 다")
    void resultTooLargeFails() {
        insertRunning(1L, 3);
        String tooLarge = "\"" + "x".repeat(properties().result().maxJsonBytes()) + "\"";

        assertThat(processor.succeed(1L, WORKER, 1, tooLarge)).isTrue();

        Job saved = repository.get(1L);
        assertThat(saved.status()).isEqualTo(JobStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo(Job.RESULT_TOO_LARGE);
        assertThat(saved.resultJson()).isNull();
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("재시도할 수 있는 실패 — 시도가 남았으면 백오프 뒤 QUEUED(이벤트 없음), 상한이면 FAILED(이벤트 1회)")
    void failRetriesUntilLimit() {
        insertRunning(1L, 2);
        assertThat(processor.fail(1L, WORKER, 1, "UPSTREAM_TIMEOUT", "응답 없음", true)).isTrue();
        Job queued = repository.get(1L);
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(queued.scheduledAt()).isAfter(NOW);
        assertThat(queued.errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(events).isEmpty();

        repository.overwrite(queued.claim(WORKER, queued.scheduledAt(), LEASE));
        assertThat(processor.fail(1L, WORKER, 2, "UPSTREAM_TIMEOUT", "응답 없음", true)).isTrue();
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.FAILED);
        assertThat(events).singleElement().isInstanceOfSatisfying(JobFinishedEvent.class,
            event -> assertThat(event.errorCode()).isEqualTo("UPSTREAM_TIMEOUT"));
    }

    @Test
    @DisplayName("재시도할 수 없는 실패는 시도가 남아도 바로 FAILED 다")
    void nonRetryableFailsImmediately() {
        insertRunning(1L, 3);

        assertThat(processor.fail(1L, WORKER, 1, "BAD_INPUT", null, false)).isTrue();

        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.FAILED);
        assertThat(repository.get(1L).attempt()).isEqualTo(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("취소 요청 — QUEUED 는 바로 CANCELLED(이벤트), RUNNING 은 플래그만(이벤트 없음 · 반복해도 같음), 끝난 작업은 거절한다")
    void requestCancel() {
        repository.insert(Job.register(1L, "REPORT_EXPORT", "{}", null, null, 0, NOW, 3));
        assertThat(processor.requestCancel(1L, 7L).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(events).hasSize(1);

        insertRunning(2L, 3);
        Job requested = processor.requestCancel(2L, 7L);
        assertThat(requested.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(repository.get(2L).cancelRequested()).isTrue();
        assertThat(processor.requestCancel(2L, 7L).cancelRequested()).isTrue();
        assertThat(events).hasSize(1);

        assertThatThrownBy(() -> processor.requestCancel(1L, 7L))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.JOB_ALREADY_FINISHED));
        assertThatThrownBy(() -> processor.requestCancel(99L, 7L))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.NOT_FOUND_JOB));
    }

    @Test
    @DisplayName("graceful shutdown 의 돌려놓기 — 이 워커의 시도를 QUEUED 로 돌리고 시도 번호를 되돌린다")
    void releaseRestoresAttempt() {
        insertRunning(1L, 3);

        assertThat(processor.release(1L, WORKER, 1)).isTrue();

        Job released = repository.get(1L);
        assertThat(released.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(released.attempt()).isZero();
        assertThat(released.isClaimable(NOW)).isTrue();
        assertThat(events).isEmpty();
    }

    private Job insertRunning(long jobId, int maxAttempts) {
        return repository.insert(Job.register(jobId, "REPORT_EXPORT", "{}", null, null, 0, NOW, maxAttempts).claim(WORKER, NOW, LEASE));
    }
}
