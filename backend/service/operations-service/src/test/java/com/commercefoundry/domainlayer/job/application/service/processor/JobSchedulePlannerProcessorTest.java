package com.commercefoundry.domainlayer.job.application.service.processor;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.handler;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.handlers;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobPlannerLockPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobRegistration;
import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobSchedulePlannerProcessorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = at(2026, 10, 26, 10, 0);
    private static final String DEDUP_KEY = "planner:DAILY:Asia/Seoul:2026-10-26T09:00:00";
    private static final JobPlannerLockPort FREE = new FreeLock();
    private static final JobPlannerLockPort HELD = new HeldLock();

    @Test
    @DisplayName("가장 최근 슬롯을 시스템 작업으로 등록한다")
    void registersTheLatestSlot() {
        JobRegistrationProcessor registration = registering();
        JobSchedulePlannerProcessor processor = processor(registration, Duration.ofDays(32), daily("REPORT_EXPORT", LocalTime.of(9, 0)));

        assertThat(processor.registerDue(NOW)).isEqualTo(1);

        ArgumentCaptor<JobRequest> request = ArgumentCaptor.forClass(JobRequest.class);
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
        JobRegistrationProcessor registration = registering();
        JobSchedulePlannerProcessor processor = processor(repository, registration, Duration.ofDays(32), FREE, daily("REPORT_EXPORT", LocalTime.of(9, 0)));

        assertThat(saved.dedupKey()).isEqualTo(DEDUP_KEY);
        assertThat(repository.get(saved.id()).activeDedupKey()).isNull();
        assertThat(processor.registerDue(NOW)).isZero();
        verify(registration, never()).register(any());
    }

    @Test
    @DisplayName("catch-up 밖의 슬롯과 업무 오류는 건너뛰고, 다음 스케줄은 등록한다")
    void skipsOldAndRejectedThenRegistersTheNext() {
        JobRegistrationProcessor registration = mock(JobRegistrationProcessor.class);
        when(registration.register(any())).thenAnswer(invocation -> {
            JobRequest request = invocation.getArgument(0);
            if ("OTHER_JOB".equals(request.type())) {
                throw new JobException(JobErrorCode.JOB_PAYLOAD_TOO_LARGE);
            }
            return created(request);
        });
        JobSchedulePlannerProcessor processor = processor(
            registration, Duration.ofMinutes(30),
            daily("REPORT_EXPORT", LocalTime.of(8, 0)), daily("OTHER_JOB", LocalTime.of(9, 30)), daily("REPORT_EXPORT", LocalTime.of(9, 45))
        );

        assertThat(processor.registerDue(NOW)).isEqualTo(1);
        ArgumentCaptor<JobRequest> request = ArgumentCaptor.forClass(JobRequest.class);
        verify(registration, times(2)).register(request.capture());
        assertThat(request.getAllValues()).extracting(JobRequest::type).containsExactly("OTHER_JOB", "REPORT_EXPORT");
        assertThat(request.getAllValues().get(1).dedupKey()).isEqualTo("planner:DAILY:Asia/Seoul:2026-10-26T09:45:00");
    }

    @Test
    @DisplayName("동시 등록에서 진 슬롯(JOB_DEDUP_CONFLICT)은 0 건으로 세고 다음 스케줄을 계속 등록한다")
    void dedupConflictCountsAsNothingAndContinues() {
        JobRegistrationProcessor registration = mock(JobRegistrationProcessor.class);
        when(registration.register(any()))
            .thenThrow(new JobException(JobErrorCode.JOB_DEDUP_CONFLICT))
            .thenAnswer(invocation -> created(invocation.getArgument(0)));
        JobSchedulePlannerProcessor processor = processor(
            registration, Duration.ofDays(32), daily("REPORT_EXPORT", LocalTime.of(9, 0)), daily("OTHER_JOB", LocalTime.of(9, 30))
        );

        assertThat(processor.registerDue(NOW)).isEqualTo(1);
        verify(registration, times(2)).register(any());
    }

    @Test
    @DisplayName("예상 밖 예외는 남은 스케줄을 등록하지 않고 그대로 나간다 — 다음 주기가 다시 시도한다")
    void unexpectedFailureStopsTheRun() {
        JobRegistrationProcessor registration = mock(JobRegistrationProcessor.class);
        when(registration.register(any())).thenThrow(new IllegalStateException("db down"));
        JobSchedulePlannerProcessor processor = processor(
            registration, Duration.ofDays(32), daily("REPORT_EXPORT", LocalTime.of(9, 0)), daily("OTHER_JOB", LocalTime.of(9, 30))
        );

        assertThatThrownBy(() -> processor.plan(NOW)).isInstanceOf(IllegalStateException.class).hasMessage("db down");
        verify(registration, times(1)).register(any());
    }

    @Test
    @DisplayName("락을 못 잡으면 등록하지 않고 0 이고, 잡으면 등록 수를 돌려준다")
    void lockDecidesWhetherToRegister() {
        JobRegistrationProcessor heldRegistration = registering();
        JobSchedulePlannerProcessor held = processor(
            new InMemoryJobRepositoryPort(), heldRegistration, Duration.ofDays(32), HELD, daily("REPORT_EXPORT", LocalTime.of(9, 0))
        );
        JobSchedulePlannerProcessor free = processor(registering(), Duration.ofDays(32), daily("REPORT_EXPORT", LocalTime.of(9, 0)));

        assertThat(held.plan(NOW)).isZero();
        verify(heldRegistration, never()).register(any());
        assertThat(free.plan(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("핸들러가 없는 종류의 스케줄은 기동에서 실패한다 — 꺼져 있거나 스케줄이 없으면 보지 않는다")
    void requiresAHandlerForEveryScheduledType() {
        JobHandlerProcessor onlyReport = new JobHandlerProcessor(handlers(handler("REPORT_EXPORT")), properties());
        JobProperties.Planner.Schedule unknown = daily("UNKNOWN_JOB", LocalTime.of(9, 0));

        assertThatThrownBy(() -> processor(onlyReport, true, daily("REPORT_EXPORT", LocalTime.of(9, 0)), unknown, unknown(LocalTime.of(10, 0))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[UNKNOWN_JOB]");
        assertThatCode(() -> processor(onlyReport, false, unknown)).doesNotThrowAnyException();
        assertThatCode(() -> processor(onlyReport, true)).doesNotThrowAnyException();
    }

    private static JobProperties.Planner.Schedule unknown(LocalTime time) {
        return daily("UNKNOWN_JOB", time);
    }

    private static JobRegistrationProcessor registering() {
        JobRegistrationProcessor registration = mock(JobRegistrationProcessor.class);
        when(registration.register(any())).thenAnswer(invocation -> created(invocation.getArgument(0)));
        return registration;
    }

    private static JobRegistration created(JobRequest request) {
        return new JobRegistration(
            Job.register(1L, request.type(), request.payloadJson(), request.dedupKey(), null, request.priority(), request.scheduledAt(), 3), true
        );
    }

    private static JobSchedulePlannerProcessor processor(
        JobRegistrationProcessor registration, Duration catchUp, JobProperties.Planner.Schedule... schedules
    ) {
        return processor(new InMemoryJobRepositoryPort(), registration, catchUp, FREE, schedules);
    }

    private static JobSchedulePlannerProcessor processor(
        InMemoryJobRepositoryPort repository, JobRegistrationProcessor registration, Duration catchUp, JobPlannerLockPort lock,
        JobProperties.Planner.Schedule... schedules
    ) {
        JobHandlerProcessor allTypes = new JobHandlerProcessor(handlers(handler("REPORT_EXPORT"), handler("OTHER_JOB")), properties());
        return new JobSchedulePlannerProcessor(withPlanner(true, catchUp, schedules), repository, registration, allTypes, lock);
    }

    private static JobSchedulePlannerProcessor processor(JobHandlerProcessor handlers, boolean enabled, JobProperties.Planner.Schedule... schedules) {
        return new JobSchedulePlannerProcessor(
            withPlanner(enabled, Duration.ofDays(32), schedules), new InMemoryJobRepositoryPort(), mock(JobRegistrationProcessor.class), handlers, FREE
        );
    }

    private static JobProperties withPlanner(boolean enabled, Duration catchUp, JobProperties.Planner.Schedule... schedules) {
        JobProperties defaults = properties();
        JobProperties.Planner planner = new JobProperties.Planner(
            enabled, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(5), catchUp, List.of(schedules)
        );
        return new JobProperties(defaults.worker(), defaults.leaseSweep(), defaults.retry(), defaults.result(), planner);
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

    /** 락을 잡는다 — 바로 실행한다. */
    private static final class FreeLock implements JobPlannerLockPort {

        @Override
        public <T> Optional<T> runLocked(Supplier<T> action) {
            return Optional.ofNullable(action.get());
        }
    }

    /** 다른 인스턴스가 쥐고 있다 — 실행하지 않는다. */
    private static final class HeldLock implements JobPlannerLockPort {

        @Override
        public <T> Optional<T> runLocked(Supplier<T> action) {
            return Optional.empty();
        }
    }
}
