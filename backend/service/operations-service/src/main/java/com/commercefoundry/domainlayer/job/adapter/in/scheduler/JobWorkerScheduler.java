package com.commercefoundry.domainlayer.job.adapter.in.scheduler;

import com.commercefoundry.domainlayer.job.application.port.in.JobWorkerSchedulerUseCase;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.global.support.WarnThrottle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 작업 워커 폴링 ({@code architecture-guide.md} "백그라운드 작업 실행"). 주기는 {@code operations.jobs.worker.poll-interval} 이고,
 * {@code operations.jobs.worker.enabled=false} 면 이 빈을 만들지 않는다 — 그 인스턴스는 등록만 하고 실행은 다른 인스턴스가 한다.
 *
 * <p>인스턴스가 여럿이어도 분산 락을 두지 않는다 — 같은 작업을 둘이 가져가면 {@code @Version} 이 하나만 통과시킨다.
 * 예외는 잡아 로그로 남긴다. DB 장애처럼 같은 원인이 이어지면 주기마다 쌓지 않고 간격당 한 번만 warn 한다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = "operations.jobs.worker.enabled")
public class JobWorkerScheduler {

    private final JobWorkerSchedulerUseCase jobWorkerUseCase;
    private final WarnThrottle warnings;

    public JobWorkerScheduler(JobWorkerSchedulerUseCase jobWorkerUseCase, JobProperties jobProperties) {
        this.jobWorkerUseCase = jobWorkerUseCase;
        this.warnings = new WarnThrottle(jobProperties.worker().heartbeatInterval());
    }

    @Scheduled(
        initialDelayString = "${operations.jobs.worker.initial-delay}",
        fixedDelayString = "${operations.jobs.worker.poll-interval}"
    )
    public void poll() {
        try {
            jobWorkerUseCase.poll();
        } catch (RuntimeException exception) {
            long suppressed = warnings.tryAcquire(exception.getClass().getName());
            if (suppressed >= 0) {
                log.warn("job poll failed, will retry. error={}, suppressedSinceLastWarn={}", exception.getClass().getSimpleName(), suppressed, exception);
            }
        }
    }
}
