package com.commercefoundry.domainlayer.job.domain.model;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Builder;

/**
 * 백그라운드 작업 한 건. 상태는 아래 도메인 메서드의 허용 전이 표로만 바뀐다 — setter 를 두지 않는다 ({@code architecture-guide.md} §5).
 * 전이는 새 인스턴스를 만든다. 저장은 {@code @Version} 비교로 동시 전이를 하나만 통과시킨다.
 *
 * <table>
 *   <caption>허용 전이 (표 밖은 {@link JobTransitionException} — Processor 가 409 오류 코드로 바꾼다)</caption>
 *   <tr><th>현재</th><th>claim</th><th>succeed</th><th>fail</th><th>cancel</th><th>requestCancel</th><th>release</th></tr>
 *   <tr><td>QUEUED (예약 시각 지남)</td><td>RUNNING (attempt+1)</td><td>✕</td><td>✕</td><td>CANCELLED</td><td>CANCELLED</td><td>✕</td></tr>
 *   <tr><td>QUEUED (예약 시각 전)</td><td>✕</td><td>✕</td><td>✕</td><td>CANCELLED</td><td>CANCELLED</td><td>✕</td></tr>
 *   <tr><td>RUNNING (lease 유효)</td><td>✕</td><td>SUCCEEDED</td><td>QUEUED(재시도) · FAILED · CANCELLED(취소 요청됨)</td><td>CANCELLED</td>
 *       <td>그대로 + 취소 요청 플래그 (멱등)</td><td>QUEUED (attempt−1) · CANCELLED(취소 요청됨)</td></tr>
 *   <tr><td>RUNNING (lease 만료)</td><td>RUNNING (새 워커, attempt+1) · 상한이면 FAILED({@link #LEASE_EXPIRED}) · 취소 요청됨이면 CANCELLED</td>
 *       <td colspan="5">lease 유효와 같다 — 끝내기 전에 Processor 가 {@link #isOwnedBy} 로 아직 자기 실행인지 확인한다</td></tr>
 *   <tr><td>SUCCEEDED · FAILED · CANCELLED</td><td colspan="6">✕ (끝난 작업)</td></tr>
 * </table>
 *
 * <p>{@link #activeDedupKey()} 는 QUEUED · RUNNING 동안만 멱등 키이고 끝나면 null 이다. 유니크 키({@code uk_job_type_active_dedup_key})가 같은 키의 활성 작업을
 * 하나로 막고, 끝난 키는 다시 등록할 수 있다 — PostgreSQL 유니크 키는 null 을 겹치게 둔다 ({@code uk_stock_reservation_active_order_id} 와 같은 방식).
 *
 * @param priority 클수록 먼저 가져간다
 * @param payloadJson 핸들러 입력 JSON ({@link #MAX_PAYLOAD_BYTES} 이하). 개인정보 원문을 싣지 않는다
 * @param dedupKey 멱등 키. 없으면 null
 * @param requestedByMemberId 등록한 어드민 (auth-service member id). 시스템 등록이면 null
 * @param scheduledAt 이 시각부터 가져갈 수 있다. 재시도면 백오프가 더해진 시각이다
 * @param attempt 지금까지 가져간 시도 수 (0 부터). RUNNING 이면 지금 실행 중인 시도 번호다
 * @param workerId RUNNING 이면 쥔 워커. 끝난 작업은 마지막에 실행한 워커를 남긴다. QUEUED 는 null
 * @param leaseExpiresAt RUNNING 일 때만 있다. heartbeat 가 연장하고, 이 시각이 지나면 다른 워커가 회수한다
 * @param startedAt RUNNING 이면 이번 시도를 가져간 시각
 * @param finishedAt 끝난 작업(SUCCEEDED · FAILED · CANCELLED)만 있다
 * @param progressPercent 0 ~ 100. 보고 전이면 null
 * @param cancelRequested 취소 요청을 받았다 — RUNNING 이면 다음 checkpoint 에서 멈춘다
 * @param cancelRequestedByMemberId 취소를 요청한 어드민. 없으면 null
 * @param errorCode 마지막 실패 코드 — 재시도 대기(QUEUED) 동안에도 남는다. 성공하면 지운다
 * @param version 낙관적 락 버전. 아직 저장하지 않은 작업은 null 이다
 */
@Builder(toBuilder = true, access = AccessLevel.PRIVATE)
public record Job(
    long id, String type, JobStatus status, int priority, String payloadJson,
    String dedupKey, Long requestedByMemberId, Instant scheduledAt,
    int attempt, int maxAttempts, String workerId, Instant leaseExpiresAt, Instant startedAt, Instant finishedAt,
    Integer progressPercent, String progressMessage, boolean cancelRequested, Long cancelRequestedByMemberId,
    String resultJson, String errorCode, String errorMessage, Long version
) {

    /** 작업 종류 형식 — 핸들러 {@link JobHandler#type()} 도 같다. */
    public static final Pattern TYPE_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");
    /** payload 상한 (UTF-8 바이트). 큰 입력은 아이디만 싣고 실행 시점에 읽는다. */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    public static final int DEDUP_KEY_MAX_LENGTH = 200;
    public static final int WORKER_ID_MAX_LENGTH = 100;
    public static final int PROGRESS_MESSAGE_MAX_LENGTH = 200;
    public static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 워커가 죽어 lease 가 만료됐고 시도 상한에 닿았다. */
    public static final String LEASE_EXPIRED = "LEASE_EXPIRED";
    /** 한 시도가 실행 시간 상한을 넘었다. 다시 시도하지 않는다. */
    public static final String RUN_TIMEOUT = "RUN_TIMEOUT";
    /** 핸들러가 {@link JobExecutionException} 이 아닌 예외를 냈다. 다시 시도한다. */
    public static final String HANDLER_ERROR = "HANDLER_ERROR";
    /** 결과 JSON 이 상한을 넘었다. 다시 시도해도 같아 시도하지 않는다. */
    public static final String RESULT_TOO_LARGE = "RESULT_TOO_LARGE";

    public Job {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException("작업 종류는 [A-Z0-9_]{1,64} 이어야 합니다: " + type);
        }
        if (dedupKey != null && (dedupKey.isBlank() || dedupKey.length() > DEDUP_KEY_MAX_LENGTH)) {
            throw new IllegalArgumentException("멱등 키는 1 ~ " + DEDUP_KEY_MAX_LENGTH + "자여야 합니다.");
        }
        if (maxAttempts < 1 || attempt < 0 || attempt > maxAttempts) {
            throw new IllegalArgumentException("시도 수는 0 ~ maxAttempts(1 이상)여야 합니다. attempt=" + attempt + ", maxAttempts=" + maxAttempts);
        }
        if (status == JobStatus.QUEUED && (attempt >= maxAttempts || workerId != null)) {
            throw new IllegalArgumentException("QUEUED 는 남은 시도가 있고 워커가 없어야 합니다. attempt=" + attempt);
        }
        if ((status == JobStatus.RUNNING) != (leaseExpiresAt != null)) {
            throw new IllegalArgumentException("lease 는 RUNNING 일 때만 있어야 합니다: " + status);
        }
        if (status == JobStatus.RUNNING && (workerId == null || startedAt == null || attempt < 1)) {
            throw new IllegalArgumentException("RUNNING 은 워커 · 시작 시각 · 시도 번호가 있어야 합니다.");
        }
        if (status.isFinished() != (finishedAt != null)) {
            throw new IllegalArgumentException("종료 시각은 끝난 작업만 있어야 합니다: " + status);
        }
        if (progressPercent != null && (progressPercent < 0 || progressPercent > 100)) {
            throw new IllegalArgumentException("진행률은 0 ~ 100 이어야 합니다: " + progressPercent);
        }
        if (cancelRequestedByMemberId != null && !cancelRequested) {
            throw new IllegalArgumentException("취소 요청자는 취소 요청이 있을 때만 있어야 합니다.");
        }
    }

    /**
     * 새 작업 (QUEUED, 시도 0). 저장 전이라 버전이 없다 — 첫 저장은 INSERT 다.
     *
     * @throws IllegalArgumentException payload 가 {@link #MAX_PAYLOAD_BYTES} 를 넘는다 — Processor 가 먼저 막고 오류 코드를 붙인다
     */
    public static Job register(
        long id, String type, String payloadJson, String dedupKey, Long requestedByMemberId, int priority, Instant scheduledAt, int maxAttempts
    ) {
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (payloadBytes(payloadJson) > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload 는 " + MAX_PAYLOAD_BYTES + " 바이트 이하여야 합니다.");
        }
        return new Job(
            id, type, JobStatus.QUEUED, priority, payloadJson, dedupKey, requestedByMemberId, scheduledAt,
            0, maxAttempts, null, null, null, null, null, null, false, null, null, null, null, null
        );
    }

    /** payload 의 UTF-8 바이트 수. */
    public static int payloadBytes(String payloadJson) {
        return payloadJson.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 활성(QUEUED · RUNNING) 동안만 멱등 키이고 끝나면 null — 끝난 키는 다시 등록할 수 있다. */
    public String activeDedupKey() {
        return status.isActive() ? dedupKey : null;
    }

    /** 지금 워커가 가져갈 수 있는가 — 예약 시각이 지난 QUEUED, 또는 lease 가 만료된 RUNNING. */
    public boolean isClaimable(Instant now) {
        return status == JobStatus.QUEUED && !scheduledAt.isAfter(now)
            || status == JobStatus.RUNNING && leaseExpiresAt.isBefore(now);
    }

    /** 아직 이 워커의 이 시도인가 — 끝내기 전 확인. 다른 워커가 회수했거나 끝났으면 늦게 끝난 실행의 결과를 버린다. */
    public boolean isOwnedBy(String workerId, int attempt) {
        return status == JobStatus.RUNNING && this.workerId.equals(workerId) && this.attempt == attempt;
    }

    /**
     * 워커가 가져간다. 예약 시각이 지난 QUEUED 면 새 시도를 시작한다. lease 가 만료된 RUNNING(워커가 죽었다)이면 새 워커가 다음 시도로 회수하는데,
     * 시도 상한이면 FAILED({@link #LEASE_EXPIRED}), 취소 요청을 받았으면 CANCELLED 로 끝낸다. 결과가 RUNNING 일 때만 실행한다.
     *
     * @throws JobTransitionException 가져갈 수 없다 ({@link #isClaimable} 이 false)
     */
    public Job claim(String workerId, Instant now, Duration lease) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (workerId.isBlank() || workerId.length() > WORKER_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("워커 아이디는 1 ~ " + WORKER_ID_MAX_LENGTH + "자여야 합니다.");
        }
        if (!isClaimable(now)) {
            throw new JobTransitionException(status, JobTransitionException.Action.CLAIM);
        }
        if (status == JobStatus.RUNNING && cancelRequested) {
            return finish(JobStatus.CANCELLED, now).build();
        }
        if (status == JobStatus.RUNNING && attempt >= maxAttempts) {
            return finish(JobStatus.FAILED, now).errorCode(LEASE_EXPIRED).errorMessage("워커 응답이 끊겨 lease 가 만료됐고 시도 상한에 닿았습니다.").build();
        }
        JobBuilder next = toBuilder()
            .status(JobStatus.RUNNING)
            .attempt(attempt + 1)
            .workerId(workerId)
            .leaseExpiresAt(now.plus(lease))
            .startedAt(now)
            .progressPercent(null)
            .progressMessage(null);
        if (status == JobStatus.RUNNING) {
            next.errorCode(LEASE_EXPIRED).errorMessage("이전 시도의 워커 응답이 끊겨 lease 가 만료돼 다시 실행합니다.");
        }
        return next.build();
    }

    /**
     * 성공으로 끝낸다. 진행률은 100 이고 이전 시도의 오류는 지운다.
     *
     * @throws JobTransitionException 실행 중이 아니다
     */
    public Job succeed(String resultJson, Instant now) {
        requireRunning(JobTransitionException.Action.SUCCEED);
        return finish(JobStatus.SUCCEEDED, now).resultJson(resultJson).progressPercent(100).errorCode(null).errorMessage(null).build();
    }

    /**
     * 실패를 기록한다. 취소 요청을 받았으면 CANCELLED, 재시도할 수 있고 시도가 남았으면 백오프 뒤 QUEUED, 아니면 FAILED 다.
     *
     * @param message 짧은 설명 (개인정보 없이). {@link #ERROR_MESSAGE_MAX_LENGTH} 자를 넘으면 자른다. 없으면 null
     * @throws JobTransitionException 실행 중이 아니다
     */
    public Job fail(String errorCode, String message, boolean retryable, Instant now, RetryPolicy retryPolicy) {
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        requireRunning(JobTransitionException.Action.FAIL);
        String trimmed = message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH ? message : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
        if (cancelRequested) {
            return finish(JobStatus.CANCELLED, now).errorCode(errorCode).errorMessage(trimmed).build();
        }
        if (retryable && attempt < maxAttempts) {
            return toQueued(now.plus(retryPolicy.delayAfter(attempt)), attempt).errorCode(errorCode).errorMessage(trimmed).build();
        }
        return finish(JobStatus.FAILED, now).errorCode(errorCode).errorMessage(trimmed).build();
    }

    /**
     * 취소로 끝낸다 — QUEUED 를 바로 끝내거나, 실행 중 취소 요청을 받은 워커가 멈춘 뒤 끝낸다.
     *
     * @throws JobTransitionException 이미 끝났다
     */
    public Job cancel(Instant now) {
        if (status.isFinished()) {
            throw new JobTransitionException(status, JobTransitionException.Action.CANCEL);
        }
        return finish(JobStatus.CANCELLED, now).build();
    }

    /**
     * 어드민 취소 요청. QUEUED 는 바로 CANCELLED 이고, RUNNING 은 플래그만 세운다 — 워커가 다음 checkpoint 에서 멈추고 {@link #cancel} 로 끝낸다.
     * 이미 요청된 RUNNING 이면 그대로다(멱등).
     *
     * @param memberId 요청한 어드민. 시스템 요청이면 null
     * @throws JobTransitionException 이미 끝났다 (Processor 가 409 로 바꾼다)
     */
    public Job requestCancel(Long memberId, Instant now) {
        return switch (status) {
            case QUEUED -> finish(JobStatus.CANCELLED, now).cancelRequested(true).cancelRequestedByMemberId(memberId).build();
            case RUNNING -> cancelRequested ? this : toBuilder().cancelRequested(true).cancelRequestedByMemberId(memberId).build();
            case SUCCEEDED, FAILED, CANCELLED -> throw new JobTransitionException(status, JobTransitionException.Action.REQUEST_CANCEL);
        };
    }

    /**
     * 실행 중인 작업을 돌려놓는다(graceful shutdown 에서 끝나지 못한 작업). 시도를 쓰지 않은 것으로 보고 attempt 를 되돌려 바로 다시 가져갈 수 있게 한다 —
     * 핸들러가 attempt 단위로 멱등이라 같은 번호로 다시 실행해도 된다. 취소 요청을 받았으면 CANCELLED 로 끝낸다.
     *
     * @throws JobTransitionException 실행 중이 아니다
     */
    public Job release(Instant now) {
        requireRunning(JobTransitionException.Action.RELEASE);
        if (cancelRequested) {
            return finish(JobStatus.CANCELLED, now).build();
        }
        return toQueued(now, attempt - 1).build();
    }

    private void requireRunning(JobTransitionException.Action action) {
        if (status != JobStatus.RUNNING) {
            throw new JobTransitionException(status, action);
        }
    }

    /** 끝난 작업. lease 를 비우고 멱등 키 칸({@link #activeDedupKey()})이 null 이 된다. 마지막 워커는 남긴다. */
    private JobBuilder finish(JobStatus finished, Instant now) {
        Objects.requireNonNull(now, "now");
        return toBuilder().status(finished).leaseExpiresAt(null).finishedAt(now);
    }

    private JobBuilder toQueued(Instant scheduledAt, int attempt) {
        return toBuilder()
            .status(JobStatus.QUEUED)
            .scheduledAt(scheduledAt)
            .attempt(attempt)
            .workerId(null)
            .leaseExpiresAt(null)
            .startedAt(null);
    }
}
