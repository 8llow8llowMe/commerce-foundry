package com.commercefoundry.domainlayer.job.adapter.in.scheduler;

import com.commercefoundry.domainlayer.job.application.port.in.JobSchedulePlannerUseCase;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.global.support.WarnThrottle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 작업 등록 트리거 ({@code architecture-guide.md} §10). 주기 안에 도래한 일 · 주 · 월 슬롯을 작업으로 등록한다.
 * {@code operations.jobs.planner.enabled=false} 면 이 빈을 만들지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "operations.jobs.planner.enabled", havingValue = "true")
public class JobSchedulePlanner {

    private final JobSchedulePlannerUseCase jobSchedulePlannerUseCase;
    private final WarnThrottle warnings;

    public JobSchedulePlanner(JobSchedulePlannerUseCase jobSchedulePlannerUseCase, JobProperties jobProperties) {
        this.jobSchedulePlannerUseCase = jobSchedulePlannerUseCase;
        this.warnings = new WarnThrottle(jobProperties.planner().fixedDelay());
    }

    @Scheduled(
        initialDelayString = "${operations.jobs.planner.initial-delay}",
        fixedDelayString = "${operations.jobs.planner.fixed-delay}"
    )
    public void plan() {
        try {
            int created = jobSchedulePlannerUseCase.plan();
            if (created > 0) {
                log.info("scheduled jobs registered. count={}", created);
            }
        } catch (RuntimeException exception) {
            long suppressed = warnings.tryAcquire(exception.getClass().getName());
            if (suppressed >= 0) {
                log.warn("scheduled job planner failed, will retry. error={}, suppressedSinceLastWarn={}",
                    exception.getClass().getSimpleName(), suppressed, exception);
            }
        }
    }
}
