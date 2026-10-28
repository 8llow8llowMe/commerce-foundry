package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.application.port.in.JobSchedulePlannerUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobSchedulePlannerProcessor;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 정기 작업 등록 진입. 스케줄이 없으면 락을 잡지 않는다 — {@code planner.enabled=false} 면 트리거 빈이 없어 여기까지 오지 않는다.
 * 락과 작업 한 건의 트랜잭션은 Processor 가 연다.
 */
@Service
@RequiredArgsConstructor
public class JobSchedulePlannerFacade implements JobSchedulePlannerUseCase {

    private final JobSchedulePlannerProcessor jobSchedulePlannerProcessor;
    private final JobProperties jobProperties;
    private final Clock clock;

    @Override
    public int plan() {
        if (jobProperties.planner().schedules().isEmpty()) {
            return 0;
        }
        return jobSchedulePlannerProcessor.plan(clock.instant());
    }
}
