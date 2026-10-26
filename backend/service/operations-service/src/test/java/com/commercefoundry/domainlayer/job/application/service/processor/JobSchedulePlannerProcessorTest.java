package com.commercefoundry.domainlayer.job.application.service.processor;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.job.application.command.JobRegisterCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobRegistrationInfo;
import com.commercefoundry.domainlayer.job.application.port.in.JobRegisterUseCase;
import com.commercefoundry.domainlayer.job.application.port.out.JobPlannerLockPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobSchedulePlannerProcessorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = at(2026, 10, 26, 10, 0);
    private static final String DEDUP_KEY = "planner:DAILY:Asia/Seoul:2026-10-26T09:00:00";

    @Test
    @DisplayName("가장 최근 슬롯을 시스템 작업으로 등록한다")
    void registersTheLatestSlot() {
        InMemoryJobRepositoryPort repository = new InMemoryJobRepositoryPort();
        JobRegisterUseCase registration = mock(JobRegisterUseCase.class);
        when(registration.register(any())).thenReturn(new JobRegistrationInfo(1L, "REPORT_EXPORT", JobStatus.QUEUED, true));
        JobSchedulePlannerProcessor processor = processor(repository, registration, Duration.ofDays(32), daily("REPORT_EXPORT", LocalTime.of(9, 0)));

        assertThat(processor.registerDue(NOW)).isEqualTo(1);

        ArgumentCaptor<JobRegisterCommand> request = ArgumentCaptor.forClass(JobRegisterCommand.class);
        verify(registration).register(request.capture());
        assertThat(request.getValue().type()).isEqualTo("REPORT_EXPORT");
        assertThat(request.getValue().dedupKey()).isEqualTo(DEDUP_KEY);
        assertThat(request.getValue().requestedByMemberId()).isNull();
        assertThat(request.getValue().scheduledAt()).isEqualTo(at(2026, 10, 26, 9, 0));
        assertThat(request.getValue().payloadJson()).isEqualTo("{}");
    }

    @Test
    @DisplayName("끝난 슬롯은 활성 키가 비어도 다시 등록하지 않는다")
    void skipsASlotThatAlreadyFinished() {
        InMemoryJobRepositoryPort repository = new InMemoryJobRepositoryPort();
        Job saved = repository.insert(queued(DEDUP_KEY));
        repository.update(saved.claim("worker", NOW, Duration.ofMinutes(5)).succeed("{}", NOW));
        JobRegisterUseCase registration = mock(JobRegisterUseCase.class);
        JobSchedulePlannerProcessor processor = processor(repository, registration, Duration.ofDays(32), daily("REPORT_EXPORT", LocalTime.of(9, 0)));

        assertThat(saved.dedupKey()).isEqualTo(DEDUP_KEY);
        assertThat(repository.get(saved.id()).activeDedupKey()).isNull();
        assertThat(processor.registerDue(NOW)).isZero();
        verify(registration, never()).register(any());
    }

    @Test
    @DisplayName("catch-up 밖의 슬롯과 모르는 종류는 건너뛰고, 다음 스케줄은 등록한다")
    void skipsOldAndUnknownThenRegistersTheNext() {
        InMemoryJobRepositoryPort repository = new InMemoryJobRepositoryPort();
        JobRegisterUseCase registration = mock(JobRegisterUseCase.class);
        when(registration.register(any())).thenAnswer(invocation -> {
            JobRegisterCommand request = invocation.getArgument(0);
            if ("UNKNOWN_JOB".equals(request.type())) {
                throw new JobException(JobErrorCode.UNKNOWN_JOB_TYPE, request.type());
            }
            return new JobRegistrationInfo(2L, request.type(), JobStatus.QUEUED, true);
        });
        JobSchedulePlannerProcessor processor = processor(
            repository, registration, Duration.ofMinutes(30),
            daily("REPORT_EXPORT", LocalTime.of(8, 0)), daily("UNKNOWN_JOB", LocalTime.of(9, 30)), daily("REPORT_EXPORT", LocalTime.of(9, 45))
        );

        assertThat(processor.registerDue(NOW)).isEqualTo(1);
        ArgumentCaptor<JobRegisterCommand> request = ArgumentCaptor.forClass(JobRegisterCommand.class);
        verify(registration, times(2)).register(request.capture());
        assertThat(request.getAllValues()).extracting(JobRegisterCommand::type).containsExactly("UNKNOWN_JOB", "REPORT_EXPORT");
        assertThat(request.getAllValues().get(1).dedupKey()).isEqualTo("planner:DAILY:Asia/Seoul:2026-10-26T09:45:00");
    }

    @Test
    @DisplayName("락을 못 잡으면 등록하지 않고, 잡으면 등록 수를 돌려준다")
    void lockDecidesWhetherToRegister() {
        JobRegisterUseCase registration = mock(JobRegisterUseCase.class);
        when(registration.register(any())).thenReturn(new JobRegistrationInfo(1L, "REPORT_EXPORT", JobStatus.QUEUED, true));
        JobSchedulePlannerProcessor held = processor(
            new InMemoryJobRepositoryPort(), registration, Duration.ofDays(32), action -> { }, daily("REPORT_EXPORT", LocalTime.of(9, 0))
        );
        JobSchedulePlannerProcessor free = processor(
            new InMemoryJobRepositoryPort(), registration, Duration.ofDays(32), Runnable::run, daily("REPORT_EXPORT", LocalTime.of(9, 0))
        );

        assertThat(held.plan(NOW)).isZero();
        assertThat(free.plan(NOW)).isEqualTo(1);
    }

    private static JobSchedulePlannerProcessor processor(
        InMemoryJobRepositoryPort repository, JobRegisterUseCase registration, Duration catchUp, JobProperties.Planner.Schedule... schedules
    ) {
        return processor(repository, registration, catchUp, Runnable::run, schedules);
    }

    private static JobSchedulePlannerProcessor processor(
        InMemoryJobRepositoryPort repository, JobRegisterUseCase registration, Duration catchUp, JobPlannerLockPort lock,
        JobProperties.Planner.Schedule... schedules
    ) {
        JobProperties defaults = properties();
        JobProperties.Planner planner = new JobProperties.Planner(true, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(5), catchUp, List.of(schedules));
        return new JobSchedulePlannerProcessor(
            new JobProperties(defaults.worker(), defaults.leaseSweep(), defaults.retry(), defaults.result(), planner),
            repository, registration, lock
        );
    }

    private static JobProperties.Planner.Schedule daily(String type, LocalTime time) {
        return new JobProperties.Planner.Schedule(type, JobProperties.Planner.Cadence.DAILY, time, SEOUL, null, null, null, 0);
    }

    private static Job queued(String dedupKey) {
        return Job.register(1L, "REPORT_EXPORT", "{}", dedupKey, null, 0, at(2026, 10, 26, 9, 0), 3);
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SEOUL).toInstant();
    }
}
