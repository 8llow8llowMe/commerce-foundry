package com.commercefoundry.domainlayer.job.adapter.in.scheduler;

import com.commercefoundry.domainlayer.job.application.port.in.JobWorkerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 작업 워커의 graceful shutdown. 가장 늦은 phase({@link Integer#MAX_VALUE})라 기동은 마지막, 종료는 <b>가장 먼저</b> 한다 — 웹 서버 · 실행 스레드 풀 ·
 * heartbeat 스케줄러 · outbox 릴레이가 아직 살아 있을 때 새 신호 받기를 멈추고(받은 신호는 늦춰 다시 받는다 — 이 인스턴스의 리스너 컨테이너가 멈춘 뒤
 * 리밸런스로 다른 인스턴스에 넘어간다), 실행 중인 작업을
 * {@code shutdown-grace} 까지 기다린 뒤, 남은 이 워커의 RUNNING 작업을 QUEUED 로 돌려놓는다(시도 번호를 되돌리고 실행 신호를 다시 낸다).
 * 기다리는 동안 heartbeat 가 lease 를 계속 연장한다.
 *
 * <p>프로세스가 강제로 죽으면 이 단계가 없다 — 그때는 lease 가 끝난 뒤 만료 판정이 다음 시도로 회수한다. 컨테이너 종료 유예(예: k8s
 * {@code terminationGracePeriodSeconds})는 {@code shutdown-grace} 보다 길게 둔다 — 짧으면 돌려놓기 전에 죽어 lease 만료까지 기다린다.
 * 컨텍스트가 다시 시작되면({@link #start()}) 신호를 다시 받는다.
 */
@Component
@RequiredArgsConstructor
public class JobWorkerLifecycle implements SmartLifecycle {

    private final JobWorkerUseCase jobWorkerUseCase;
    private volatile boolean running;

    @Override
    public void start() {
        jobWorkerUseCase.resume();
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
