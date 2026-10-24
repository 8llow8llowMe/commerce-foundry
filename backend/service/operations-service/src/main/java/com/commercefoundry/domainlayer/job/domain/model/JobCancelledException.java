package com.commercefoundry.domainlayer.job.domain.model;

import java.util.Objects;
import lombok.Getter;

/**
 * {@link JobExecution#checkpoint} 가 던지는 중단 신호. 핸들러는 잡지 않고 내보낸다 — 워커가 사유에 따라 끝낸다.
 */
@Getter
public class JobCancelledException extends RuntimeException {

    private final Reason reason;

    public JobCancelledException(Reason reason) {
        super("작업 실행을 멈춥니다. reason=" + reason);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** 멈춘 이유. */
    public enum Reason {
        /** 어드민이 취소를 요청했다 — CANCELLED 로 끝난다 */
        CANCEL_REQUESTED,
        /** heartbeat 가 lease 를 잃었다(다른 워커가 회수 · 종료 중 반환) — 결과를 쓰지 않고 버린다 */
        LEASE_LOST,
        /** 실행 시간 상한({@link JobHandlerOptions#maxRunDuration()})을 넘었다 — FAILED({@link Job#RUN_TIMEOUT})로 끝난다 */
        RUN_TIMEOUT
    }
}
