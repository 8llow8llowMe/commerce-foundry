package com.commercefoundry.domainlayer.job.application.port.in;

/**
 * 작업 워커 (스케줄러 · 종료 lifecycle 진입점). 폴링 한 번이 후보 조회 → 건별 가져가기 → 실행 스레드 제출이다. 실행은 트랜잭션 밖 전용 스레드에서 한다.
 */
public interface JobWorkerSchedulerUseCase {

    /**
     * 빈 실행 칸만큼 작업을 가져가 실행 스레드에 넘긴다. 종료 중이면 아무것도 하지 않는다.
     *
     * @return 이번에 실행을 시작한 작업 수
     */
    int poll();

    /**
     * graceful shutdown — 폴링을 멈추고, 실행 중인 작업을 {@code operations.jobs.worker.shutdown-grace} 까지 기다린 뒤, 그래도 남은 이 워커의 RUNNING 작업을
     * QUEUED 로 돌려놓는다(다른 인스턴스 · 다음 기동이 바로 가져간다).
     */
    void shutdown();
}
