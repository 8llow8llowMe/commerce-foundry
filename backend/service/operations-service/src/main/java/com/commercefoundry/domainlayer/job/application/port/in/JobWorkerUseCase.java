package com.commercefoundry.domainlayer.job.application.port.in;

import com.commercefoundry.domainlayer.job.application.info.JobDispatchResult;

/**
 * 작업 워커 (실행 신호 컨슈머 · 종료 lifecycle 진입점). 신호 한 건이 실행 칸 확보 → 가져가기 → 실행 스레드 제출이다. 실행은 트랜잭션 밖 전용 스레드에서 한다
 * ({@code architecture-guide.md} §10).
 */
public interface JobWorkerUseCase {

    /**
     * 실행 신호가 가리킨 시도를 가져가 실행 스레드에 넘긴다. 실행이 끝나기를 기다리지 않는다.
     *
     * @param attempt 신호에 실린 시도 번호
     * @param type 신호에 실린 작업 종류
     * @return 시작함 · 미룸(같은 시도의 신호를 늦게 다시 냄) · 버림 · 늦춰 다시 받기
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code UNKNOWN_JOB_TYPE} 이 인스턴스에 핸들러가 없는 종류 — 다시 받아도 같다
     */
    JobDispatchResult dispatch(long jobId, int attempt, String type);

    /** 신호를 다시 받는다 — {@link #shutdown()} 뒤 lifecycle 이 다시 시작될 때(컨텍스트 재시작). 처음 기동에는 이미 받는 상태다. */
    void resume();

    /**
     * graceful shutdown — 새 신호를 받지 않고, 실행 중인 작업을 {@code operations.jobs.worker.shutdown-grace} 까지 기다린 뒤, 그래도 남은 이 워커의 RUNNING 작업을
     * QUEUED 로 돌려놓는다(돌려놓은 작업의 실행 신호를 다시 내 다른 인스턴스 · 다음 기동이 바로 가져간다).
     */
    void shutdown();
}
