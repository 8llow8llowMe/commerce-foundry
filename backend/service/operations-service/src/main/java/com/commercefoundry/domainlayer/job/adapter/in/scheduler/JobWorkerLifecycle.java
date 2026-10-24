package com.commercefoundry.domainlayer.job.adapter.in.scheduler;

import com.commercefoundry.domainlayer.job.application.port.in.JobWorkerSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 작업 워커의 graceful shutdown. 가장 늦은 phase({@link Integer#MAX_VALUE})라 기동은 마지막, 종료는 <b>가장 먼저</b> 한다 — 웹 서버 · 실행 스레드 풀 ·
 * heartbeat 스케줄러가 아직 살아 있을 때 폴링을 멈추고, 실행 중인 작업을 {@code shutdown-grace} 까지 기다린 뒤, 남은 이 워커의 RUNNING 작업을 QUEUED 로
 * 돌려놓는다(시도 번호를 되돌린다). 기다리는 동안 heartbeat 가 lease 를 계속 연장한다.
 *
 * <p>프로세스가 강제로 죽으면 이 단계가 없다 — 그때는 lease 가 끝난 뒤 다른 워커가 다음 시도로 회수한다.
 */
@Component
@RequiredArgsConstructor
public class JobWorkerLifecycle implements SmartLifecycle {

    private final JobWorkerSchedulerUseCase jobWorkerUseCase;
    private volatile boolean running;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        if (running) {
            running = false;
            jobWorkerUseCase.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
