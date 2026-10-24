package com.commercefoundry.domainlayer.job.application.service;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.handlers;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.job.application.service.processor.JobClaimProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobCancelledException;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.global.support.JobWorkerIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;

/**
 * 워커 Facade 의 가져가기 · 제출 · heartbeat · 종료 기록을 가짜 저장소로 본다. 실행 스레드는 테스트가 고른다 — 같은 스레드에서 바로 돌리거나 제출을 거절한다.
 * heartbeat 스케줄러는 예약된 작업을 붙잡아 두기만 한다 — 핸들러 안에서 테스트가 직접 한 번 돌린다. 실제 스레드 · 주기 · lease 회수는 {@code JobWorkerFlowTest} 가 본다.
 */
class JobWorkerSchedulerFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final String TYPE = "REPORT_EXPORT";
    private static final String WORKER = "operations-service:test:1:00000000";

    private InMemoryJobRepositoryPort repository;
    private List<Object> events;
    private MutableClock clock;
    private TaskScheduler scheduler;
    private Runnable heartbeat;

    @BeforeEach
    void setUp() {
        repository = new InMemoryJobRepositoryPort();
        events = new ArrayList<>();
        clock = new MutableClock(NOW);
        scheduler = mock(TaskScheduler.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class))).thenAnswer(invocation -> {
            heartbeat = invocation.getArgument(0);
            return mock(ScheduledFuture.class);
        });
        repository.insert(Job.register(1L, TYPE, "{}", null, null, 0, NOW, 3));
    }

    @Test
    @DisplayName("실행 스레드가 제출을 거절하면 가져간 작업을 바로 QUEUED 로 돌려놓는다 — 시도 번호 · 칸이 되돌아가고 종료 이벤트는 없다")
    void submitRejectedReleases() {
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("no free thread");
        };
        JobWorkerSchedulerFacade facade = facade(rejecting, handler(execution -> JobOutcome.empty()));

        assertThat(facade.poll()).isZero();

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attempt()).isZero();
        assertThat(job.isClaimable(NOW)).isTrue();
        assertThat(facade.activeCount()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("가져가 실행하고 성공을 기록한다 — SUCCEEDED · 결과 JSON · 시도 1, 종료 이벤트 1회, 칸을 돌려준다")
    void runsAndRecordsSuccess() {
        JobWorkerSchedulerFacade facade = facade(Runnable::run, handler(execution -> new JobOutcome("{\"rows\":3}")));

        assertThat(facade.poll()).isEqualTo(1);

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.resultJson()).isEqualTo("{\"rows\":3}");
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.workerId()).isEqualTo(WORKER);
        assertThat(events).singleElement().isInstanceOf(JobFinishedEvent.class);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("실행 중 다른 쪽이 작업을 가져가면(lease 상실 · 돌려놓음) 늦게 끝난 결과를 버린다 — 상태를 덮지 않고 이벤트도 없다")
    void discardsResultWhenNoLongerOwned() {
        JobWorkerSchedulerFacade facade = facade(Runnable::run, handler(execution -> {
            repository.overwrite(repository.get(execution.jobId()).release(NOW));
            return new JobOutcome("{\"rows\":3}");
        }));

        facade.poll();

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.resultJson()).isNull();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("heartbeat 가 진행률을 쓰고 lease 를 연장한다")
    void heartbeatWritesProgress() {
        JobWorkerSchedulerFacade facade = facade(Runnable::run, handler(execution -> {
            execution.checkpoint(40, "40%");
            clock.advance(Duration.ofSeconds(30));
            heartbeat.run();
            Job running = repository.get(execution.jobId());
            assertThat(running.progressPercent()).isEqualTo(40);
            assertThat(running.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30).plus(Duration.ofMinutes(5)));
            return JobOutcome.empty();
        }));

        facade.poll();

        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("heartbeat 가 취소 요청을 보면 멈춤 신호를 주고, 핸들러가 checkpoint 에서 멈추면 CANCELLED 로 끝낸다")
    void heartbeatSeesCancelRequest() {
        JobWorkerSchedulerFacade facade = facade(Runnable::run, handler(execution -> {
            repository.requestCancel(execution.jobId(), 9L, NOW);
            heartbeat.run();
            assertThat(execution.isCancelRequested()).isTrue();
            assertThatThrownBy(() -> execution.checkpoint(50, null)).isInstanceOf(JobCancelledException.class);
            execution.checkpoint(50, null);
            return JobOutcome.empty();
        }));

        facade.poll();

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(events).singleElement().isInstanceOf(JobFinishedEvent.class);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("실행 시간 상한을 넘으면 heartbeat 가 바로 FAILED(RUN_TIMEOUT, 재시도 없음)로 끝낸다 — 핸들러가 늦게 돌려준 결과는 버린다")
    void heartbeatFailsOnRunTimeout() {
        JobWorkerSchedulerFacade facade = facade(Runnable::run, handler(execution -> {
            clock.advance(Duration.ofMinutes(30));
            heartbeat.run();
            Job failed = repository.get(execution.jobId());
            assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failed.errorCode()).isEqualTo(Job.RUN_TIMEOUT);
            return new JobOutcome("{\"late\":true}");
        }));

        facade.poll();

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.resultJson()).isNull();
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(events).singleElement().isInstanceOf(JobFinishedEvent.class);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("heartbeat 예약이 거절되면 핸들러를 부르지 않고 QUEUED 로 돌려놓는다 — lease 를 연장할 수 없으면 실행하지 않는다")
    void heartbeatRejectedReleases() {
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
            .thenThrow(new TaskRejectedException("scheduler down"));
        AtomicBoolean invoked = new AtomicBoolean();
        JobWorkerSchedulerFacade facade = facade(Runnable::run, handler(execution -> {
            invoked.set(true);
            return JobOutcome.empty();
        }));

        facade.poll();

        Job job = repository.get(1L);
        assertThat(invoked).isFalse();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attempt()).isZero();
        assertThat(events).isEmpty();
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("종료 신호를 받은 뒤에는 새로 가져가지 않는다")
    void doesNotPollAfterShutdown() {
        JobWorkerSchedulerFacade facade = facade(Runnable::run, handler(execution -> JobOutcome.empty()));

        facade.shutdown();

        assertThat(facade.poll()).isZero();
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.QUEUED);
    }

    private JobWorkerSchedulerFacade facade(TaskExecutor executor, JobHandler handler) {
        JobHandlerProcessor handlerProcessor = new JobHandlerProcessor(handlers(handler), properties());
        return new JobWorkerSchedulerFacade(
            new JobClaimProcessor(repository, events::add, clock),
            new JobRunProcessor(repository, properties(), events::add, clock),
            handlerProcessor, properties(), executor, scheduler, clock, new JobWorkerIdentity(WORKER)
        );
    }

    private static JobHandler handler(Function<JobExecution, JobOutcome> body) {
        return new JobHandler() {
            @Override
            public String type() {
                return TYPE;
            }

            @Override
            public JobOutcome execute(JobExecution execution) {
                return body.apply(execution);
            }
        };
    }

    /** 테스트가 앞으로 돌리는 시계. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
