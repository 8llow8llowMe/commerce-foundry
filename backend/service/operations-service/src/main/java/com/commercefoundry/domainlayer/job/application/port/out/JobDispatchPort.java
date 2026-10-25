package com.commercefoundry.domainlayer.job.application.port.out;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import java.time.Instant;

/**
 * 작업 실행 신호 ({@code architecture-guide.md} §10). QUEUED 작업의 "이 시도를 실행하라" 를 워커들에게 나눠 보낸다 — 상태 · 멱등 · lease 의 정본은 {@code operations.job} 이고
 * 신호에는 아이디 · 종류 · 시도 번호 · 예약 시각만 싣는다.
 *
 * <p>트랜잭션 안에서 부른다 — 함께 커밋되고 함께 롤백된다(transactional outbox). {@code availableAt} 전에는 나가지 않는다(지연 발행).
 * 같은 신호가 두 번 가도 워커의 가져가기가 시도 번호로 한 번만 통과시킨다.
 */
public interface JobDispatchPort {

    /** 예약 시각에 나가는 신호. @param job 막 저장한 QUEUED 작업 */
    default void dispatch(Job job) {
        dispatch(job, job.scheduledAt());
    }

    /**
     * @param job QUEUED 작업
     * @param availableAt 이 시각부터 보낸다 — 워커가 지금 실행할 수 없어 신호를 미룰 때 예약 시각보다 늦게 둔다
     */
    void dispatch(Job job, Instant availableAt);
}
