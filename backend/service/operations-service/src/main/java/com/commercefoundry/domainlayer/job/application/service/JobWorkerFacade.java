package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobDispatchResult;
import com.commercefoundry.domainlayer.job.application.port.in.JobWorkerUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobClaimProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobClaimProcessor.ClaimResult;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor.RegisteredJobHandler;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobCancelledException;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.global.support.JobWorkerIdentity;
import com.commercefoundry.global.support.WarnThrottle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 작업 워커 — 실행 신호 처리 · 실행 · heartbeat · graceful shutdown 을 조합한다 ({@code architecture-guide.md} §10).
 *
 * <p><b>트랜잭션을 이 Facade 에 걸지 않는다.</b> 핸들러 실행은 오래 걸리고 다른 서비스를 부를 수 있어, 트랜잭션 안에서 하면 실행 내내 DB 커넥션을 쥐고
 * 원격 응답을 기다린다. 가져가기 · heartbeat · 종료 기록을 Processor 의 짧은 트랜잭션으로 나누고, 실행은 그 사이 전용 스레드
 * ({@code jobWorkerTaskExecutor})에서 트랜잭션 없이 한다. 신호를 받은 컨슈머 스레드는 가져가 넘기기까지만 하고 바로 돌아간다.
 *
 * <ul>
 *   <li>동시 실행은 인스턴스 전체 상한({@code worker.concurrency} — 실행 스레드 수)과 종류별 상한({@link RegisteredJobHandler#options()})을 넘지 않는다.
 *       전체 칸이 차거나 종료 중이면 신호를 늦춰 다시 받는다({@code worker.redelivery-delay} — 컨슈머가 그동안 멈춘다). 종류별 칸만 찼거나 예약 시각 전이면
 *       같은 시도의 신호를 outbox 로 늦게 다시 내고 받은 신호는 끝낸다 — 다른 종류 신호를 막지 않는다. 그래도 제출이 거절되면(큐 0 — 끝난 스레드가
 *       풀로 돌아오기 직전) 가져간 작업을 바로 QUEUED 로 돌려놓는다 — 돌려놓기가 실행 신호를 다시 낸다.</li>
 *   <li>heartbeat 는 별도 스케줄러({@code jobLeaseTaskScheduler})가 주기마다 lease 를 연장한다. 0행이면 lease 를 잃었거나 취소 요청을 받은 것이라
 *       실행에 멈춤 신호를 준다. 실행 시간 상한을 넘으면 그 자리에서 FAILED({@code RUN_TIMEOUT}, 재시도 없음)로 기록하고 멈춤 신호를 준다 —
 *       핸들러가 checkpoint 를 부르지 않아도 작업은 끝난다(스레드는 핸들러가 돌아올 때까지 칸을 쥔다).</li>
 *   <li>같은 시도의 heartbeat · 종료 기록 · 돌려놓기는 {@link RunningJob#lock()} 으로 줄을 선다 — 자기 heartbeat 와 버전 충돌을 내지 않는다.
 *       취소 요청 같은 다른 쓰기와 겹친 버전 충돌은 다시 읽어 몇 번 더 기록한다.</li>
 * </ul>
 */
@Slf4j
@Service
public class JobWorkerFacade implements JobWorkerUseCase {

    /** 종료 기록이 취소 요청 등과 겹쳐 버전 충돌이 나면 다시 읽어 기록하는 횟수. */
    private static final int FINISH_ATTEMPTS = 3;
    /** 예약 시각 전 신호를 미루는 최소 간격 — 서버 시계 차로 남은 시간이 0 이하여도 바로 되돌아오지 않게 한다. */
    private static final Duration MIN_REDELIVERY_DELAY = Duration.ofMillis(100);

    private final JobClaimProcessor jobClaimProcessor;
    private final JobRunProcessor jobRunProcessor;
    private final JobHandlerProcessor jobHandlerProcessor;
    private final JobProperties.Worker worker;
    private final TaskExecutor jobWorkerTaskExecutor;
    private final TaskScheduler jobLeaseTaskScheduler;
    private final Clock clock;
    private final String workerId;
    private final WarnThrottle heartbeatWarnings;

    private final Map<Long, RunningJob> running = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final Map<String, AtomicInteger> activeByType = new ConcurrentHashMap<>();
    /** 신호 처리 한 번과 종료가 겹치지 않게 한다 — 종료가 돌려놓기 목록을 만든 뒤 진행 중이던 신호 처리가 새 작업을 가져가지 않게. */
    private final Object dispatchLock = new Object();
    private volatile boolean accepting = true;

    public JobWorkerFacade(
        JobClaimProcessor jobClaimProcessor,
        JobRunProcessor jobRunProcessor,
        JobHandlerProcessor jobHandlerProcessor,
        JobProperties jobProperties,
        @Qualifier("jobWorkerTaskExecutor") TaskExecutor jobWorkerTaskExecutor,
        @Qualifier("jobLeaseTaskScheduler") TaskScheduler jobLeaseTaskScheduler,
        Clock clock,
        JobWorkerIdentity jobWorkerIdentity
    ) {
        this.jobClaimProcessor = jobClaimProcessor;
        this.jobRunProcessor = jobRunProcessor;
        this.jobHandlerProcessor = jobHandlerProcessor;
        this.worker = jobProperties.worker();
        this.jobWorkerTaskExecutor = jobWorkerTaskExecutor;
        this.jobLeaseTaskScheduler = jobLeaseTaskScheduler;
        this.clock = clock;
        this.workerId = jobWorkerIdentity.id();
        this.heartbeatWarnings = new WarnThrottle(worker.heartbeatInterval());
        log.info("job worker ready. workerId={}, concurrency={}, types={}", workerId, worker.concurrency(), jobHandlerProcessor.types());
    }

    /** 이 인스턴스에서 실행 중인 작업 수 (테스트용). */
    int activeCount() {
        return active.get();
    }

    @Override
    public JobDispatchResult dispatch(long jobId, int attempt, String type) {
        RegisteredJobHandler handler = jobHandlerProcessor.find(type)
            .orElseThrow(() -> new JobException(JobErrorCode.UNKNOWN_JOB_TYPE, type));
        Instant deferUntil;
        synchronized (dispatchLock) {
            if (!accepting) {
                return JobDispatchResult.redeliver(worker.redeliveryDelay());
            }
            Slot slot = reserveSlot(type, handler.options().maxConcurrencyPerInstance());
            if (slot == Slot.INSTANCE_FULL) {
                log.debug("job worker busy, redelivering. jobId={}, type={}, attempt={}", jobId, type, attempt);
                return JobDispatchResult.redeliver(worker.redeliveryDelay());
            }
            if (slot == Slot.TYPE_FULL) {
                deferUntil = clock.instant().plus(worker.redeliveryDelay());
            } else {
                ClaimResult claimed;
                try {
                    claimed = jobClaimProcessor.claim(jobId, attempt, type, workerId, handler.options().leaseDuration());
                } catch (JobException exception) {
                    releaseSlot(type);
                    if (exception.getErrorCode() != JobErrorCode.JOB_CONFLICT) {
                        throw exception;
                    }
                    log.debug("job claimed by another worker. jobId={}, attempt={}", jobId, attempt);
                    return JobDispatchResult.SKIPPED;
                } catch (RuntimeException exception) {
                    releaseSlot(type);
                    throw exception;
                }
                if (claimed.outcome() == ClaimResult.Outcome.CLAIMED) {
                    return start(claimed.job(), handler);
                }
                releaseSlot(type);
                if (claimed.outcome() == ClaimResult.Outcome.STALE) {
                    log.debug("job signal stale, skipped. jobId={}, type={}, attempt={}", jobId, type, attempt);
                    return JobDispatchResult.SKIPPED;
                }
                deferUntil = notBefore(claimed.scheduledAt());
            }
        }
        return defer(jobId, attempt, type, deferUntil);
    }

    @Override
    public void resume() {
        accepting = true;
    }

    @Override
    public void shutdown() {
        accepting = false;
        synchronized (dispatchLock) {
            // 진행 중이던 신호 처리가 끝났다 — 이제 새로 가져가는 작업은 없다
            log.info("job worker stopping. workerId={}, running={}", workerId, running.size());
        }
        long deadline = System.nanoTime() + worker.shutdownGrace().toNanos();
        while (active.get() > 0 && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (RunningJob execution : List.copyOf(running.values())) {
            releaseOnShutdown(execution);
        }
        log.info("job worker stopped. workerId={}", workerId);
    }

    /**
     * 가져간 시도를 실행 스레드에 넘긴다(칸은 잡혀 있다). 넘기지 못했으면 칸을 돌려주고 시도를 QUEUED 로 돌려놓는다 — 돌려놓기가 실행 신호를 다시 내므로
     * 이 신호는 버린다.
     */
    private JobDispatchResult start(Job claimed, RegisteredJobHandler handler) {
        RunningJob execution = new RunningJob(claimed);
        running.put(claimed.id(), execution);
        try {
            jobWorkerTaskExecutor.execute(() -> run(execution, handler));
            log.info("job started. jobId={}, type={}, attempt={}", claimed.id(), claimed.type(), execution.attempt());
            return JobDispatchResult.STARTED;
        } catch (RuntimeException exception) {
            // 큐 0 이라 거절(TaskRejectedException)이 보통이지만, 종료 중인 실행기 등 다른 예외여도 가져간 시도를 쥔 채 두지 않는다
            log.warn("job submit rejected, releasing. jobId={}, type={}, attempt={}, error={}",
                claimed.id(), claimed.type(), execution.attempt(), exception.getClass().getSimpleName());
            running.remove(claimed.id(), execution);
            releaseSlot(claimed.type());
            releaseQuietly(execution);
            return JobDispatchResult.SKIPPED;
        }
    }

    /** 같은 시도의 신호를 {@code availableAt} 에 다시 내고 받은 신호는 끝낸다. 그 사이 다른 쪽이 가져갔거나 끝냈으면 버린다. */
    private JobDispatchResult defer(long jobId, int attempt, String type, Instant availableAt) {
        if (!jobClaimProcessor.defer(jobId, attempt, type, availableAt)) {
            log.debug("job signal stale, skipped. jobId={}, type={}, attempt={}", jobId, type, attempt);
            return JobDispatchResult.SKIPPED;
        }
        log.debug("job signal deferred. jobId={}, type={}, attempt={}, availableAt={}", jobId, type, attempt, availableAt);
        return JobDispatchResult.DEFERRED;
    }

    /** 예약 시각 — 인스턴스 시계 차로 이미 지난 시각이면 최소 간격 뒤다(릴레이가 바로 다시 보내 되돌아오지 않게). */
    private Instant notBefore(Instant scheduledAt) {
        Instant earliest = clock.instant().plus(MIN_REDELIVERY_DELAY);
        return scheduledAt.isAfter(earliest) ? scheduledAt : earliest;
    }

    /** 실행 스레드. 핸들러를 트랜잭션 없이 부르고, 끝난 모양에 따라 종료를 기록한다. */
    private void run(RunningJob execution, RegisteredJobHandler handler) {
        Duration heartbeatInterval = worker.heartbeatInterval();
        try {
            try {
                // 스케줄러의 트리거는 시스템 시계로 돈다 — 주입한 Clock 이 아니라 Instant.now() 로 첫 시각을 잡는다
                execution.attachHeartbeat(jobLeaseTaskScheduler.scheduleWithFixedDelay(
                    () -> heartbeat(execution, handler), Instant.now().plus(heartbeatInterval), heartbeatInterval
                ));
            } catch (TaskRejectedException exception) {
                // lease 를 연장할 수 없으면 실행하지 않는다 — 실행 도중 lease 가 끝나 다른 워커와 겹친다
                log.warn("job heartbeat rejected, releasing. jobId={}, type={}, attempt={}", execution.jobId(), execution.type(), execution.attempt());
                releaseQuietly(execution);
                return;
            }
            JobOutcome outcome = handler.handler().execute(execution);
            String resultJson = outcome == null ? null : outcome.resultJson();
            record(execution, () -> jobRunProcessor.succeed(execution.jobId(), execution.workerId(), execution.attempt(), resultJson));
        } catch (JobCancelledException exception) {
            onStopped(execution, exception.getReason());
        } catch (JobExecutionException exception) {
            log.warn("job attempt failed. jobId={}, type={}, attempt={}, errorCode={}, retryable={}",
                execution.jobId(), execution.type(), execution.attempt(), exception.getErrorCode(), exception.isRetryable());
            record(execution, () -> jobRunProcessor.fail(
                execution.jobId(), execution.workerId(), execution.attempt(), exception.getErrorCode(), exception.getMessage(), exception.isRetryable()
            ));
        } catch (RuntimeException exception) {
            // 예상 밖 예외의 메시지는 외부 응답 · 입력값을 담을 수 있어 저장하지 않는다 — 클래스 이름만 남긴다
            String errorType = exception.getClass().getSimpleName();
            log.error("job handler threw. jobId={}, type={}, attempt={}, error={}", execution.jobId(), execution.type(), execution.attempt(), errorType, exception);
            record(execution, () -> jobRunProcessor.fail(
                execution.jobId(), execution.workerId(), execution.attempt(), Job.HANDLER_ERROR, errorType, true
            ));
        } finally {
            execution.cancelHeartbeat();
            running.remove(execution.jobId(), execution);
            releaseSlot(execution.type());
        }
    }

    private void onStopped(RunningJob execution, JobCancelledException.Reason reason) {
        switch (reason) {
            case LEASE_LOST -> log.warn("job attempt abandoned, lease lost. jobId={}, type={}, attempt={}",
                execution.jobId(), execution.type(), execution.attempt());
            case RUN_TIMEOUT -> record(execution, () -> jobRunProcessor.fail(
                execution.jobId(), execution.workerId(), execution.attempt(), Job.RUN_TIMEOUT, "실행 시간 상한을 넘었습니다.", false
            ));
            case CANCEL_REQUESTED -> record(execution, () -> jobRunProcessor.cancelled(execution.jobId(), execution.workerId(), execution.attempt()));
        }
    }

    /**
     * 종료를 기록한다. heartbeat 와 줄을 서고, 종료 중 돌려놓은 시도면 쓰지 않는다. 다른 쓰기(취소 요청)와 겹친 버전 충돌은 다시 읽어 기록한다.
     * 그래도 실패하면 RUNNING 으로 남고, lease 가 끝나면 만료 판정이 회수한다(핸들러는 attempt 단위로 멱등이다).
     */
    private void record(RunningJob execution, BooleanSupplier write) {
        execution.lock().lock();
        try {
            if (execution.isClosed()) {
                return;
            }
            execution.close();
            for (int attempt = 1; ; attempt++) {
                try {
                    write.getAsBoolean();
                    return;
                } catch (JobException exception) {
                    if (exception.getErrorCode() != JobErrorCode.JOB_CONFLICT || attempt >= FINISH_ATTEMPTS) {
                        throw exception;
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.error("job finish not recorded, lease will expire. jobId={}, type={}, attempt={}, error={}",
                execution.jobId(), execution.type(), execution.attempt(), exception.getClass().getSimpleName(), exception);
        } finally {
            execution.lock().unlock();
        }
    }

    /** heartbeat 스레드. 종료 기록이 진행 중이면 건너뛴다. 예외는 잡아 원인마다 간격당 한 번만 warn 한다 — 다음 주기에 다시 시도한다. */
    private void heartbeat(RunningJob execution, RegisteredJobHandler handler) {
        if (!execution.lock().tryLock()) {
            return;
        }
        try {
            if (execution.isClosed() || execution.stopReason() != null) {
                execution.cancelHeartbeat();
                return;
            }
            if (!clock.instant().isBefore(execution.startedAt().plus(handler.options().maxRunDuration()))) {
                // 핸들러가 checkpoint 를 부르지 않아도 여기서 바로 FAILED 로 끝낸다 — 잠금은 재진입이라 record 가 같은 잠금을 다시 잡는다.
                // 실행 스레드가 늦게 끝나면 이미 닫힌 시도라 결과를 쓰지 않는다.
                log.warn("job run timeout, failing. jobId={}, type={}, attempt={}", execution.jobId(), execution.type(), execution.attempt());
                execution.stop(JobCancelledException.Reason.RUN_TIMEOUT);
                execution.cancelHeartbeat();
                record(execution, () -> jobRunProcessor.fail(
                    execution.jobId(), execution.workerId(), execution.attempt(), Job.RUN_TIMEOUT, "실행 시간 상한을 넘었습니다.", false
                ));
                return;
            }
            JobRunProcessor.HeartbeatResult result = jobRunProcessor.heartbeat(
                execution.jobId(), execution.workerId(), execution.attempt(), execution.progressPercent(), execution.progressMessage(),
                handler.options().leaseDuration()
            );
            if (result != JobRunProcessor.HeartbeatResult.ALIVE) {
                log.warn("job heartbeat stopped. jobId={}, type={}, attempt={}, result={}", execution.jobId(), execution.type(), execution.attempt(), result);
                execution.stop(result == JobRunProcessor.HeartbeatResult.LOST
                    ? JobCancelledException.Reason.LEASE_LOST
                    : JobCancelledException.Reason.CANCEL_REQUESTED);
                execution.cancelHeartbeat();
            }
        } catch (RuntimeException exception) {
            long suppressed = heartbeatWarnings.tryAcquire(exception.getClass().getName());
            if (suppressed >= 0) {
                log.warn("job heartbeat failed, will retry. jobId={}, attempt={}, error={}, suppressedSinceLastWarn={}",
                    execution.jobId(), execution.attempt(), exception.getClass().getSimpleName(), suppressed);
            }
        } finally {
            execution.lock().unlock();
        }
    }

    /** 종료 중 끝나지 못한 시도를 돌려놓는다. 실행 스레드에는 lease 상실로 멈춤 신호를 준다 — 이 시도의 결과는 더 쓰지 않는다. */
    private void releaseOnShutdown(RunningJob execution) {
        execution.lock().lock();
        try {
            if (execution.isClosed()) {
                return;
            }
            execution.stop(JobCancelledException.Reason.LEASE_LOST);
            execution.close();
            boolean released = jobRunProcessor.release(execution.jobId(), execution.workerId(), execution.attempt());
            log.warn("job released on shutdown. jobId={}, type={}, attempt={}, released={}", execution.jobId(), execution.type(), execution.attempt(), released);
        } catch (RuntimeException exception) {
            log.error("job release on shutdown failed, lease will expire. jobId={}, attempt={}, error={}",
                execution.jobId(), execution.attempt(), exception.getClass().getSimpleName(), exception);
        } finally {
            execution.lock().unlock();
        }
    }

    /** 제출이 거절된 시도를 돌려놓는다. 실패하면 lease 가 끝난 뒤 회수된다. */
    private void releaseQuietly(RunningJob execution) {
        execution.lock().lock();
        try {
            execution.close();
            jobRunProcessor.release(execution.jobId(), execution.workerId(), execution.attempt());
        } catch (RuntimeException exception) {
            log.error("job release failed, lease will expire. jobId={}, attempt={}, error={}",
                execution.jobId(), execution.attempt(), exception.getClass().getSimpleName());
        } finally {
            execution.lock().unlock();
        }
    }

    /** 전체 · 종류별 칸을 함께 잡는다. 하나라도 차 있으면 잡지 않는다. */
    private Slot reserveSlot(String type, int typeLimit) {
        AtomicInteger typeCount = activeByType.computeIfAbsent(type, key -> new AtomicInteger());
        if (active.incrementAndGet() > worker.concurrency()) {
            active.decrementAndGet();
            return Slot.INSTANCE_FULL;
        }
        if (typeCount.incrementAndGet() > typeLimit) {
            typeCount.decrementAndGet();
            active.decrementAndGet();
            return Slot.TYPE_FULL;
        }
        return Slot.RESERVED;
    }

    /**
     * 칸 잡기 결과. 전체 칸이 차면 이 인스턴스는 무엇도 실행할 수 없어 신호를 늦춰 다시 받는다(컨슈머를 잠시 멈춘다).
     * 종류별 칸만 차면 다른 종류는 실행할 수 있어 신호를 미뤄 다시 내고 다음 신호로 간다.
     */
    private enum Slot {
        RESERVED,
        TYPE_FULL,
        INSTANCE_FULL
    }

    private void releaseSlot(String type) {
        activeByType.get(type).decrementAndGet();
        active.decrementAndGet();
    }
}
