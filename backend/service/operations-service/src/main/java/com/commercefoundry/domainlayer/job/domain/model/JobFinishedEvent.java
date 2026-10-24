package com.commercefoundry.domainlayer.job.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 작업이 끝났다(SUCCEEDED · FAILED · CANCELLED). 종료를 저장한 트랜잭션 안에서 동기로 한 번 발행한다 — 리스너(#213 알림 센터)가 같은 트랜잭션에서
 * 알림 행을 만들면 작업 종료와 알림은 함께 커밋되거나 함께 롤백된다. 커밋 뒤에 할 일(SSE 전달)은 {@code @TransactionalEventListener(AFTER_COMMIT)} 로 받는다.
 *
 * <p>payload · 결과 · 오류 메시지 · 개인정보는 싣지 않는다. 필요하면 리스너가 아이디로 다시 읽는다.
 *
 * @param requestedByMemberId 등록한 어드민. 시스템 등록(정기 작업)이면 null
 * @param errorCode FAILED · 일부 CANCELLED 의 오류 코드. 없으면 null
 */
public record JobFinishedEvent(long jobId, String type, JobStatus status, Long requestedByMemberId, String errorCode, Instant finishedAt) {

    public JobFinishedEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (status.isActive()) {
            throw new IllegalArgumentException("끝난 작업만 종료 이벤트를 낸다: " + status);
        }
    }

    /** 끝난 작업에서 만든다. */
    public static JobFinishedEvent of(Job job) {
        return new JobFinishedEvent(job.id(), job.type(), job.status(), job.requestedByMemberId(), job.errorCode(), job.finishedAt());
    }
}
