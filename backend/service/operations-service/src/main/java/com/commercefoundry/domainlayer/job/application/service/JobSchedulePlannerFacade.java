package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.application.port.in.JobSchedulePlannerUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobSchedulePlannerProcessor;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 정기 작업 등록 진입. 스케줄이 없거나 이 인스턴스에서 꺼져 있으면 락을 잡지 않는다.
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
        JobProperties.Planner planner = jobProperties.planner();
        if (!planner.enabled() || planner.schedules().isEmpty()) {
            return 0;
        }
        return jobSchedulePlannerProcessor.plan(clock.instant());
    }
}
