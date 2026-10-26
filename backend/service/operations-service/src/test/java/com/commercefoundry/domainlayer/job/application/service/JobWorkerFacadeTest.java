package com.commercefoundry.domainlayer.job.application.service;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.handlers;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobDispatchResult;
import com.commercefoundry.domainlayer.job.application.service.processor.JobClaimProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobCancelledException;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.domainlayer.job.support.RecordingJobDispatchPort;
import com.commercefoundry.global.properties.JobProperties;
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
 * 워커 Facade 의 신호 처리(가져가기 · 버리기 · 늦춰 다시 받기) · 제출 · heartbeat · 종료 기록을 가짜 저장소로 본다. 실행 스레드는 테스트가 고른다 —
 * 같은 스레드에서 바로 돌리거나, 붙잡아 두거나, 제출을 거절한다. heartbeat 스케줄러는 예약된 작업을 붙잡아 두기만 한다 — 핸들러 안에서 테스트가 직접 한 번 돌린다.
 * 실제 Kafka · 스레드 · 주기 · lease 회수는 {@code JobWorkerFlowTest} 가 본다.
 */
class JobWorkerFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final String TYPE = "REPORT_EXPORT";
    private static final String WORKER = "operations-service:test:1:00000000";
    private static final Duration REDELIVERY = properties().worker().redeliveryDelay();

    private InMemoryJobRepositoryPort repository;
    private RecordingJobDispatchPort dispatchPort;
    private List<Object> events;
    private MutableClock clock;
    private TaskScheduler scheduler;
    private Runnable heartbeat;

    @BeforeEach
    void setUp() {
        repository = new InMemoryJobRepositoryPort();
        dispatchPort = new RecordingJobDispatchPort();
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
    @DisplayName("신호를 받아 가져가 실행하고 성공을 기록한다 — STARTED, SUCCEEDED · 결과 JSON · 시도 1, 종료 이벤트 1회, 칸을 돌려준다")
    void runsAndRecordsSuccess() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> new JobOutcome("{\"rows\":3}")));

        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.STARTED);

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.resultJson()).isEqualTo("{\"rows\":3}");
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.workerId()).isEqualTo(WORKER);
        assertThat(events).singleElement().isInstanceOf(JobFinishedEvent.class);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("같은 신호가 두 번 오면 두 번째는 버린다(SKIPPED) — 이미 가져갔거나 끝났다")
    void skipsDuplicateSignal() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> JobOutcome.empty()));

        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.STARTED);
        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.SKIPPED);

        assertThat(repository.get(1L).attempt()).isEqualTo(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("시도 번호 · 종류가 다른 신호, 없는 작업의 신호는 버린다 — 상태를 바꾸지 않는다")
    void skipsStaleSignals() {
        AtomicBoolean invoked = new AtomicBoolean();
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> {
            invoked.set(true);
            return JobOutcome.empty();
        }), handler("OTHER_TYPE", execution -> JobOutcome.empty(), JobHandlerOptions.defaults()));

        assertThat(facade.dispatch(1L, 1, TYPE)).as("다른 시도").isEqualTo(JobDispatchResult.SKIPPED);
        assertThat(facade.dispatch(1L, 0, "OTHER_TYPE")).as("다른 종류").isEqualTo(JobDispatchResult.SKIPPED);
        assertThat(facade.dispatch(99L, 0, TYPE)).as("없는 작업").isEqualTo(JobDispatchResult.SKIPPED);

        assertThat(invoked).isFalse();
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.QUEUED);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("예약 시각 전 신호는 받아 넘기고 같은 시도의 신호를 예약 시각에 다시 낸다(DEFERRED) — 너무 가까우면 최소 100ms 뒤다")
    void defersNotDue() {
        repository.insert(Job.register(2L, TYPE, "{}", null, null, 0, NOW.plusMillis(50), 3));
        repository.insert(Job.register(3L, TYPE, "{}", null, null, 0, NOW.plus(Duration.ofHours(1)), 3));
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> JobOutcome.empty()));

        assertThat(facade.dispatch(2L, 0, TYPE)).isEqualTo(JobDispatchResult.DEFERRED);
        assertThat(facade.dispatch(3L, 0, TYPE)).isEqualTo(JobDispatchResult.DEFERRED);

        assertThat(dispatchPort.signalsOf(2L)).singleElement().satisfies(signal -> {
            assertThat(signal.job().attempt()).isZero();
            assertThat(signal.availableAt()).isEqualTo(NOW.plusMillis(100));
        });
        assertThat(dispatchPort.signalsOf(3L)).singleElement()
            .satisfies(signal -> assertThat(signal.availableAt()).isEqualTo(NOW.plus(Duration.ofHours(1))));
        assertThat(repository.get(2L).status()).isEqualTo(JobStatus.QUEUED);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("종류별 동시 상한이 차 있으면 가져가지 않고 다시 받기 간격 뒤의 신호를 낸다(DEFERRED) — 칸이 비면 그 신호로 가져간다")
    void defersWhenTypeSlotFull() {
        repository.insert(Job.register(2L, TYPE, "{}", null, null, 0, NOW, 3));
        List<Runnable> held = new ArrayList<>();
        JobWorkerFacade facade = facade(held::add, handler(TYPE, execution -> JobOutcome.empty(), new JobHandlerOptions(null, null, null, 1)));

        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.STARTED);
        assertThat(facade.dispatch(2L, 0, TYPE)).isEqualTo(JobDispatchResult.DEFERRED);
        assertThat(repository.get(2L).status()).as("가져가지 않았다").isEqualTo(JobStatus.QUEUED);
        assertThat(dispatchPort.signalsOf(2L)).singleElement().satisfies(signal -> {
            assertThat(signal.job().attempt()).isZero();
            assertThat(signal.availableAt()).isEqualTo(NOW.plus(REDELIVERY));
        });

        held.removeFirst().run();
        assertThat(facade.dispatch(2L, 0, TYPE)).isEqualTo(JobDispatchResult.STARTED);
    }

    @Test
    @DisplayName("늦추는 사이 작업이 바뀌었으면(취소 · 다른 시도) 신호를 다시 내지 않고 버린다")
    void skipsDeferWhenJobChanged() {
        JobClaimProcessor claimProcessor = new JobClaimProcessor(repository, dispatchPort, clock);
        Instant later = NOW.plusSeconds(5);

        assertThat(claimProcessor.defer(1L, 1, TYPE, later)).as("다른 시도").isFalse();
        assertThat(claimProcessor.defer(1L, 0, "OTHER_TYPE", later)).as("다른 종류").isFalse();
        assertThat(claimProcessor.defer(99L, 0, TYPE, later)).as("없는 작업").isFalse();
        repository.overwrite(repository.get(1L).requestCancel(9L, NOW));
        assertThat(claimProcessor.defer(1L, 0, TYPE, later)).as("취소됨").isFalse();

        assertThat(dispatchPort.dispatched()).isEmpty();
    }

    @Test
    @DisplayName("인스턴스 전체 상한(concurrency 2)이 차 있어도 늦춰 다시 받는다")
    void redeliversWhenInstanceFull() {
        repository.insert(Job.register(2L, TYPE, "{}", null, null, 0, NOW, 3));
        repository.insert(Job.register(3L, TYPE, "{}", null, null, 0, NOW, 3));
        List<Runnable> held = new ArrayList<>();
        JobWorkerFacade facade = facade(held::add, handler(execution -> JobOutcome.empty()));

        facade.dispatch(1L, 0, TYPE);
        facade.dispatch(2L, 0, TYPE);

        assertThat(facade.dispatch(3L, 0, TYPE)).isEqualTo(JobDispatchResult.redeliver(REDELIVERY));
        assertThat(facade.activeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("이 인스턴스에 핸들러가 없는 종류는 UNKNOWN_JOB_TYPE 이다 — 리스너가 DLT 로 보낸다")
    void rejectsUnknownType() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> JobOutcome.empty()));

        assertThatThrownBy(() -> facade.dispatch(1L, 0, "NOT_REGISTERED"))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.UNKNOWN_JOB_TYPE));
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    @DisplayName("가져가다 다른 워커에게 지면(버전 충돌) 버린다 — 칸을 돌려준다")
    void skipsWhenAnotherWorkerWins() {
        repository = new InMemoryJobRepositoryPort() {
            @Override
            public synchronized Job update(Job job) {
                throw new JobException(JobErrorCode.JOB_CONFLICT);
            }
        };
        repository.insert(Job.register(1L, TYPE, "{}", null, null, 0, NOW, 3));
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> JobOutcome.empty()));

        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.SKIPPED);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("실행 스레드가 제출을 거절하면 가져간 작업을 바로 QUEUED 로 돌려놓고 실행 신호를 다시 낸다 — 이 신호는 버리고 종료 이벤트는 없다")
    void submitRejectedReleases() {
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("no free thread");
        };
        JobWorkerFacade facade = facade(rejecting, handler(execution -> JobOutcome.empty()));

        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.SKIPPED);

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attempt()).isZero();
        assertThat(job.isClaimable(NOW)).isTrue();
        assertThat(facade.activeCount()).isZero();
        assertThat(events).isEmpty();
        assertThat(dispatchPort.dispatchedOf(1L)).singleElement().satisfies(signal -> assertThat(signal.attempt()).isZero());
    }

    @Test
    @DisplayName("실행 중 다른 쪽이 작업을 가져가면(lease 상실 · 돌려놓음) 늦게 끝난 결과를 버린다 — 상태를 덮지 않고 이벤트도 없다")
    void discardsResultWhenNoLongerOwned() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> {
            repository.overwrite(repository.get(execution.jobId()).release(NOW));
            return new JobOutcome("{\"rows\":3}");
        }));

        facade.dispatch(1L, 0, TYPE);

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.resultJson()).isNull();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("재시도할 수 있는 실패면 백오프 뒤 다음 시도의 실행 신호를 낸다")
    void retryDispatchesNextAttempt() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> {
            throw new JobExecutionException("UPSTREAM_DOWN", true);
        }));

        facade.dispatch(1L, 0, TYPE);

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.scheduledAt()).isAfter(NOW);
        assertThat(dispatchPort.dispatchedOf(1L)).singleElement().satisfies(signal -> {
            assertThat(signal.attempt()).isEqualTo(1);
            assertThat(signal.scheduledAt()).isEqualTo(job.scheduledAt());
        });
    }

    @Test
    @DisplayName("heartbeat 가 진행률을 쓰고 lease 를 연장한다")
    void heartbeatWritesProgress() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> {
            execution.checkpoint(40, "40%");
            clock.advance(Duration.ofSeconds(30));
            heartbeat.run();
            Job running = repository.get(execution.jobId());
            assertThat(running.progressPercent()).isEqualTo(40);
            assertThat(running.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30).plus(Duration.ofMinutes(5)));
            return JobOutcome.empty();
        }));

        facade.dispatch(1L, 0, TYPE);

        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("heartbeat 가 취소 요청을 보면 멈춤 신호를 주고, 핸들러가 checkpoint 에서 멈추면 CANCELLED 로 끝낸다")
    void heartbeatSeesCancelRequest() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> {
            repository.requestCancel(execution.jobId(), 9L, NOW);
            heartbeat.run();
            assertThat(execution.isCancelRequested()).isTrue();
            assertThatThrownBy(() -> execution.checkpoint(50, null)).isInstanceOf(JobCancelledException.class);
            execution.checkpoint(50, null);
            return JobOutcome.empty();
        }));

        facade.dispatch(1L, 0, TYPE);

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(events).singleElement().isInstanceOf(JobFinishedEvent.class);
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("실행 시간 상한을 넘으면 heartbeat 가 바로 FAILED(RUN_TIMEOUT, 재시도 없음)로 끝낸다 — 핸들러가 늦게 돌려준 결과는 버린다")
    void heartbeatFailsOnRunTimeout() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> {
            clock.advance(Duration.ofMinutes(30));
            heartbeat.run();
            Job failed = repository.get(execution.jobId());
            assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failed.errorCode()).isEqualTo(Job.RUN_TIMEOUT);
            return new JobOutcome("{\"late\":true}");
        }));

        facade.dispatch(1L, 0, TYPE);

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
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> {
            invoked.set(true);
            return JobOutcome.empty();
        }));

        facade.dispatch(1L, 0, TYPE);

        Job job = repository.get(1L);
        assertThat(invoked).isFalse();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attempt()).isZero();
        assertThat(events).isEmpty();
        assertThat(facade.activeCount()).isZero();
    }

    @Test
    @DisplayName("종료 신호를 받은 뒤에는 새로 가져가지 않고 늦춰 다시 받는다 — 리밸런스 뒤 다른 인스턴스가 받는다")
    void redeliversAfterShutdown() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> JobOutcome.empty()));

        facade.shutdown();

        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.redeliver(REDELIVERY));
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.QUEUED);
        assertThat(dispatchPort.dispatched()).as("멈춘 동안은 신호를 다시 내지 않는다 — 같은 메시지를 다시 받는다").isEmpty();
    }

    @Test
    @DisplayName("멈춘 뒤 다시 시작하면(resume) 신호를 다시 가져간다 — 컨테이너 재시작")
    void resumesAfterShutdown() {
        JobWorkerFacade facade = facade(Runnable::run, handler(execution -> JobOutcome.empty()));

        facade.shutdown();
        facade.resume();

        assertThat(facade.dispatch(1L, 0, TYPE)).isEqualTo(JobDispatchResult.STARTED);
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("종료 때 shutdown-grace 안에 끝나지 못한 시도는 QUEUED 로 돌려놓고 실행 신호를 다시 낸다")
    void shutdownReleasesRunning() {
        List<Runnable> held = new ArrayList<>();
        JobProperties defaults = properties();
        JobProperties.Worker worker = defaults.worker();
        JobProperties shortGrace = new JobProperties(
            new JobProperties.Worker(worker.enabled(), worker.concurrency(), worker.redeliveryDelay(), worker.defaultLease(), worker.heartbeatInterval(),
                worker.defaultMaxRunDuration(), Duration.ofMillis(100)),
            defaults.leaseSweep(), defaults.retry(), defaults.result(), defaults.planner()
        );
        JobWorkerFacade facade = facade(held::add, shortGrace, handler(execution -> JobOutcome.empty()));
        facade.dispatch(1L, 0, TYPE);

        facade.shutdown();

        Job job = repository.get(1L);
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attempt()).isZero();
        assertThat(dispatchPort.dispatchedOf(1L)).singleElement().satisfies(signal -> assertThat(signal.attempt()).isZero());
    }

    private JobWorkerFacade facade(TaskExecutor executor, JobHandler... handlers) {
        return facade(executor, properties(), handlers);
    }

    private JobWorkerFacade facade(TaskExecutor executor, JobProperties jobProperties, JobHandler... handlers) {
        JobHandlerProcessor handlerProcessor = new JobHandlerProcessor(handlers(handlers), jobProperties);
        return new JobWorkerFacade(
            new JobClaimProcessor(repository, dispatchPort, clock),
            new JobRunProcessor(repository, dispatchPort, jobProperties, events::add, clock),
            handlerProcessor, jobProperties, executor, scheduler, clock, new JobWorkerIdentity(WORKER)
        );
    }

    private static JobHandler handler(Function<JobExecution, JobOutcome> body) {
        return handler(TYPE, body, JobHandlerOptions.defaults());
    }

    private static JobHandler handler(String type, Function<JobExecution, JobOutcome> body, JobHandlerOptions options) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public JobHandlerOptions options() {
                return options;
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
