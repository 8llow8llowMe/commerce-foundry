package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.command.JobRegisterCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobRegistrationInfo;
import com.commercefoundry.domainlayer.job.application.model.JobScheduleSlot;
import com.commercefoundry.domainlayer.job.application.port.in.JobRegisterUseCase;
import com.commercefoundry.domainlayer.job.application.port.out.JobPlannerLockPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.service.JobScheduleSlots;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.global.support.WarnThrottle;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 도래한 정기 슬롯을 작업으로 등록한다. 같은 컨텍스트 진입이라 {@link JobRegisterUseCase} 로 등록한다.
 * 같은 슬롯은 끝난 작업까지 {@code dedup_key} 로 막는다 — 활성 멱등만 보면 성공 직후 다음 주기가 같은 슬롯을 다시 만든다.
 * 유니크 키에 진 경우({@code JOB_DEDUP_CONFLICT})는 그 슬롯의 작업이 이미 있는 것이라 다시 만들지 않는다.
 * 한 스케줄의 업무 오류는 로그만 남기고 다음 스케줄로 간다. 저장소 장애 같은 예상 밖 예외는 이 실행을 멈추고, 다음 주기가 다시 시도한다.
 */
@Slf4j
@Component
public class JobSchedulePlannerProcessor {

    private final JobProperties.Planner planner;
    private final JobRepositoryPort jobRepositoryPort;
    private final JobRegisterUseCase jobRegisterUseCase;
    private final JobPlannerLockPort jobPlannerLockPort;
    private final WarnThrottle warnings;

    public JobSchedulePlannerProcessor(
        JobProperties jobProperties, JobRepositoryPort jobRepositoryPort, JobRegisterUseCase jobRegisterUseCase, JobPlannerLockPort jobPlannerLockPort
    ) {
        this.planner = jobProperties.planner();
        this.jobRepositoryPort = jobRepositoryPort;
        this.jobRegisterUseCase = jobRegisterUseCase;
        this.jobPlannerLockPort = jobPlannerLockPort;
        this.warnings = new WarnThrottle(planner.fixedDelay());
    }

    /**
     * 락을 잡은 뒤 도래한 슬롯을 등록한다. 락을 못 잡으면 0 이다.
     *
     * @return 이번에 새로 만든 작업 수
     */
    public int plan(Instant now) {
        int[] created = {0};
        jobPlannerLockPort.runLocked(() -> created[0] = registerDue(now));
        return created[0];
    }

    /** 락을 이미 쥔 등록. 테스트가 슬롯 판정만 본다. */
    int registerDue(Instant now) {
        int created = 0;
        for (JobProperties.Planner.Schedule schedule : planner.schedules()) {
            Optional<JobScheduleSlot> slot = JobScheduleSlots.latest(schedule, now, planner.catchUp());
            if (slot.isEmpty() || jobRepositoryPort.existsByTypeAndDedupKey(schedule.type(), slot.get().dedupKey())) {
                continue;
            }
            created += register(schedule, slot.get());
        }
        return created;
    }

    private int register(JobProperties.Planner.Schedule schedule, JobScheduleSlot slot) {
        try {
            JobRegistrationInfo registration = jobRegisterUseCase.register(new JobRegisterCommand(
                schedule.type(), schedule.payloadJson(), slot.dedupKey(), null, schedule.priority(), slot.scheduledAt()
            ));
            return registration.created() ? 1 : 0;
        } catch (JobException exception) {
            if (exception.getErrorCode() == JobErrorCode.JOB_DEDUP_CONFLICT) {
                // 등록 유스케이스가 이긴 작업을 다시 읽기 전에 그 작업이 끝났어도, 슬롯 키는 재사용하지 않는다. 다음 주기의 exists 가 막는다
                log.debug("scheduled job already registered. type={}, errorCode={}", schedule.type(), exception.getErrorCode().getCode());
                return 0;
            }
            long suppressed = warnings.tryAcquire(schedule.type() + ":" + exception.getErrorCode().getCode());
            if (suppressed >= 0) {
                log.warn("scheduled job was not registered. type={}, errorCode={}, suppressedSinceLastWarn={}",
                    schedule.type(), exception.getErrorCode().getCode(), suppressed);
            }
            return 0;
        }
    }
}
