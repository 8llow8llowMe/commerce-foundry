package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.model.JobScheduleSlot;
import com.commercefoundry.domainlayer.job.application.port.out.JobPlannerLockPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.global.support.WarnThrottle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 도래한 정기 슬롯을 작업으로 등록한다. {@link JobRegistrationProcessor} 로 등록한다 — 다른 빈이라 프록시를 거쳐 슬롯마다 트랜잭션이 따로 열린다.
 * 같은 슬롯은 끝난 작업까지 {@code dedup_key} 로 막는다 — 활성 멱등만 보면 성공 직후 다음 주기가 같은 슬롯을 다시 만든다.
 * 유니크 키에 진 경우({@code JOB_DEDUP_CONFLICT})는 그 슬롯의 작업이 이미 있는 것이라 다시 만들지 않는다.
 * 한 스케줄의 업무 오류는 로그만 남기고 다음 스케줄로 간다. 저장소 장애 같은 예상 밖 예외는 이 실행을 멈추고, 다음 주기가 다시 시도한다.
 *
 * <p>핸들러가 없는 종류의 스케줄은 기동에서 실패한다 — 그대로 두면 매 주기 {@code UNKNOWN_JOB_TYPE} 만 반복된다.
 * {@link JobHandlerProcessor} 는 생성자에서 핸들러 빈을 모두 받아 두므로, 그것을 주입받는 이 생성자에서 확인하면 충분하다.
 */
@Slf4j
@Component
public class JobSchedulePlannerProcessor {

    private final JobProperties.Planner planner;
    private final JobRepositoryPort jobRepositoryPort;
    private final JobRegistrationProcessor jobRegistrationProcessor;
    private final JobPlannerLockPort jobPlannerLockPort;
    private final WarnThrottle warnings;

    public JobSchedulePlannerProcessor(
        JobProperties jobProperties, JobRepositoryPort jobRepositoryPort, JobRegistrationProcessor jobRegistrationProcessor,
        JobHandlerProcessor jobHandlerProcessor, JobPlannerLockPort jobPlannerLockPort
    ) {
        this.planner = jobProperties.planner();
        this.jobRepositoryPort = jobRepositoryPort;
        this.jobRegistrationProcessor = jobRegistrationProcessor;
        this.jobPlannerLockPort = jobPlannerLockPort;
        this.warnings = new WarnThrottle(planner.fixedDelay());
        requireHandlers(planner, jobHandlerProcessor);
    }

    /**
     * 락을 잡은 뒤 도래한 슬롯을 등록한다. 락을 못 잡으면 0 이다.
     *
     * @return 이번에 새로 만든 작업 수
     */
    public int plan(Instant now) {
        return jobPlannerLockPort.runLocked(() -> registerDue(now)).orElse(0);
    }

    /** 락을 이미 쥔 등록. 테스트가 슬롯 판정만 본다. */
    int registerDue(Instant now) {
        int created = 0;
        for (JobProperties.Planner.Schedule schedule : planner.schedules()) {
            Optional<JobScheduleSlot> slot = JobScheduleSlots.latest(schedule, now, planner.catchUp());
            if (slot.isEmpty() || jobRepositoryPort.existsByDedupKey(schedule.type(), slot.get().dedupKey())) {
                continue;
            }
            created += register(schedule, slot.get());
        }
        return created;
    }

    private int register(JobProperties.Planner.Schedule schedule, JobScheduleSlot slot) {
        try {
            return jobRegistrationProcessor.register(new JobRequest(
                schedule.type(), schedule.payloadJson(), slot.dedupKey(), null, schedule.priority(), slot.scheduledAt()
            )).created() ? 1 : 0;
        } catch (JobException exception) {
            if (exception.getErrorCode() == JobErrorCode.JOB_DEDUP_CONFLICT) {
                // 다른 등록이 같은 슬롯을 먼저 넣었다. 이긴 작업을 다시 읽지 않는다 — 끝났어도 다음 주기의 exists 가 막는다
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

    /** 꺼져 있거나 스케줄이 없으면 등록하지 않으므로 보지 않는다. */
    private static void requireHandlers(JobProperties.Planner planner, JobHandlerProcessor jobHandlerProcessor) {
        if (!planner.enabled() || planner.schedules().isEmpty()) {
            return;
        }
        List<String> missing = planner.schedules().stream()
            .map(JobProperties.Planner.Schedule::type)
            .filter(type -> jobHandlerProcessor.find(type).isEmpty())
            .distinct()
            .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("operations.jobs.planner.schedules 에 핸들러가 없는 종류가 있습니다: " + missing);
        }
    }
}
