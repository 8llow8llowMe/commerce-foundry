package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.global.support.JobWorkerIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 작업 워커 전용 스레드. {@code @Async} 를 쓰지 않는다 — 워커 Facade 가 이 풀에 직접 넣어, 동시 상한 · 거절 처리 · 종료 순서를 코드가 쥔다.
 *
 * <p>두 빈 모두 {@code defaultCandidate = false} 다 — 타입으로 주입되지 않고 {@code @Qualifier} 로만 받는다. 일반 빈으로 두면 Boot 가
 * "Executor 빈이 이미 있다" 며 {@code applicationTaskExecutor} 를 만들지 않는다(실험으로 확인, #212). TaskScheduler 빈도 같아서 {@code taskScheduler} 가
 * 빠지면 {@code @Scheduled} lease 만료 판정이 heartbeat 스레드를 같이 쓰게 된다. Boot 의 빈 조건은 기본 후보가 아닌 빈을 세지 않는다
 * ({@code OperationsServiceApplicationTests} 가 두 기본 빈이 남는지 본다).
 */
@Slf4j
@Configuration
public class JobWorkerExecutorConfig {

    /**
     * 실행기 · 스케줄러가 멈추는 lifecycle 단계. 워커 lifecycle({@code Integer.MAX_VALUE})이 먼저 멈춰 실행 중인 작업을 {@code shutdown-grace}
     * 까지 기다리고 남은 시도를 돌려놓은 뒤에 멈춘다. 같은 단계면 함께 멈춰 heartbeat 가 먼저 끊길 수 있다.
     */
    static final int SHUTDOWN_PHASE = Integer.MAX_VALUE - 1;

    /** 프로세스당 워커 아이디 하나. */
    @Bean
    public JobWorkerIdentity jobWorkerIdentity(@Value("${spring.application.name}") String applicationName) {
        return JobWorkerIdentity.of(applicationName, Job.WORKER_ID_MAX_LENGTH);
    }

    /**
     * 핸들러 실행 스레드. core = max = {@code worker.concurrency}, 큐 0 — 쌓아 두지 않고 빈 스레드가 있을 때만 받는다(워커는 빈 칸이 있을 때만 가져간다).
     * 기다리기는 워커 lifecycle 이 이미 했다 — 그 뒤 남은 스레드는 돌려놓은 시도라 빈을 닫을 때 더 기다리지 않고 interrupt 한다.
     */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor jobWorkerTaskExecutor(JobProperties jobProperties) {
        JobProperties.Worker worker = jobProperties.worker();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(worker.concurrency());
        executor.setMaxPoolSize(worker.concurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("job-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationMillis(0);
        executor.setPhase(SHUTDOWN_PHASE);
        return executor;
    }

    /**
     * heartbeat(lease 연장 · 진행률 기록) 스레드 하나. @Scheduled 스레드와 나눠, 만료 판정이 늦어져도 lease 가 끊기지 않게 한다.
     * 컨텍스트 종료 이벤트 뒤에도 받는다 — 종료 lifecycle 이 실행 중인 작업을 기다리는 동안 lease 를 계속 연장해야 한다.
     */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler jobLeaseTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("job-lease-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAcceptTasksAfterContextClose(true);
        scheduler.setPhase(SHUTDOWN_PHASE);
        scheduler.setErrorHandler(error -> log.error("job lease task failed. error={}", error.getClass().getSimpleName(), error));
        return scheduler;
    }
}
