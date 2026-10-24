package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobCancelledException;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 이 인스턴스에서 실행 중인 시도 하나 — 핸들러가 받는 {@link JobExecution} 이자 워커가 heartbeat · 종료를 맞추는 자리다.
 *
 * <p>스레드 셋이 만난다 — 실행 스레드(핸들러 · 종료 기록), heartbeat 스레드(lease 연장), 종료 스레드(돌려놓기). 상태 플래그는 volatile 이고,
 * DB 쓰기(heartbeat · 종료 · 돌려놓기)는 {@link #lock()} 으로 줄을 세운다 — 같은 시도의 heartbeat 가 종료 기록과 겹쳐 버전 충돌을 내지 않게.
 * 종료를 기록했거나 돌려놓았으면 {@link #isClosed()} 이고, 그 뒤의 heartbeat · 종료는 아무것도 하지 않는다.
 */
final class RunningJob implements JobExecution {

    private final Job job;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Integer progressPercent;
    private volatile String progressMessage;
    private volatile JobCancelledException.Reason stopReason;
    private volatile boolean closed;
    private volatile ScheduledFuture<?> heartbeat;

    /** @param job 이 워커가 가져간 RUNNING 작업 */
    RunningJob(Job job) {
        this.job = Objects.requireNonNull(job, "job");
    }

    @Override
    public long jobId() {
        return job.id();
    }

    @Override
    public String type() {
        return job.type();
    }

    @Override
    public int attempt() {
        return job.attempt();
    }

    @Override
    public String payloadJson() {
        return job.payloadJson();
    }

    @Override
    public Instant startedAt() {
        return job.startedAt();
    }

    String workerId() {
        return job.workerId();
    }

    /** 진행률을 기억만 한다 — heartbeat 가 주기마다 DB 에 쓴다. 멈춤 신호가 있으면 사유와 함께 던진다. */
    @Override
    public void checkpoint(int percent, String message) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("진행률은 0 ~ 100 이어야 합니다: " + percent);
        }
        if (message != null && message.length() > Job.PROGRESS_MESSAGE_MAX_LENGTH) {
            throw new IllegalArgumentException("진행 문구는 " + Job.PROGRESS_MESSAGE_MAX_LENGTH + "자 이하여야 합니다.");
        }
        this.progressPercent = percent;
        this.progressMessage = message;
        JobCancelledException.Reason reason = stopReason;
        if (reason != null) {
            throw new JobCancelledException(reason);
        }
    }

    @Override
    public boolean isCancelRequested() {
        return stopReason != null;
    }

    Integer progressPercent() {
        return progressPercent;
    }

    String progressMessage() {
        return progressMessage;
    }

    /** 멈춤 신호. 먼저 온 사유가 이긴다 — lease 를 잃은 뒤의 취소 요청은 lease 상실로 남는다. */
    synchronized void stop(JobCancelledException.Reason reason) {
        if (stopReason == null) {
            stopReason = Objects.requireNonNull(reason, "reason");
        }
    }

    JobCancelledException.Reason stopReason() {
        return stopReason;
    }

    ReentrantLock lock() {
        return lock;
    }

    void attachHeartbeat(ScheduledFuture<?> heartbeat) {
        this.heartbeat = heartbeat;
    }

    /** lease 연장을 멈춘다. 이미 도는 heartbeat 는 끝까지 간다 — {@link #lock()} 으로 종료 기록과 겹치지 않는다. */
    void cancelHeartbeat() {
        ScheduledFuture<?> current = heartbeat;
        if (current != null) {
            current.cancel(false);
        }
    }

    /** 종료를 기록했거나 돌려놓았다 — 이 시도의 DB 쓰기는 더 없다. {@link #lock()} 을 쥐고 부른다. */
    void close() {
        closed = true;
        cancelHeartbeat();
    }

    boolean isClosed() {
        return closed;
    }
}
