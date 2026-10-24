package com.commercefoundry.domainlayer.job.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.domainlayer.job.application.command.JobRegisterCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobRegistrationInfo;
import com.commercefoundry.domainlayer.job.application.model.JobStatusFilter;
import com.commercefoundry.domainlayer.job.application.port.in.JobAdminUseCase;
import com.commercefoundry.domainlayer.job.application.port.in.JobRegisterUseCase;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;

/**
 * 작업 엔진의 끝에서 끝 흐름 — 실제 폴러(@Scheduled) · 실행 스레드 · heartbeat 스케줄러 · H2 저장소로 본다(test 프로파일의 짧은 주기 · lease · 백오프).
 *
 * <p>#212 완료 기준: 등록은 바로 작업 아이디를 돌려주고 워커가 처리한 뒤 완료 · 실패로 바뀐다, 실패는 재시도 상한 뒤 FAILED 로 남는다.
 * 여러 인스턴스에서 두 번 돌지 않는 것은 {@code JobClaimConcurrencyTest}(PostgreSQL)가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobWorkerFlowTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private JobRegisterUseCase jobRegisterUseCase;

    @Autowired
    private JobAdminUseCase jobAdminUseCase;

    @Autowired
    private JobRepositoryPort jobRepositoryPort;

    @Autowired
    private JobRunProcessor jobRunProcessor;

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    private Clock clock;

    @Autowired
    private FinishedEvents finishedEvents;

    @Test
    @DisplayName("등록은 바로 QUEUED 작업 아이디를 돌려주고, 워커가 처리하면 SUCCEEDED · 결과 JSON · 시도 1 이 되고 종료 이벤트가 한 번 나간다")
    void registersThenSucceeds() {
        JobRegistrationInfo registered = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.OK, "{\"month\":\"2026-10\"}", 1L));

        assertThat(registered.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(registered.created()).isTrue();

        Job job = awaitJob(registered.jobId(), finished -> finished.status() == JobStatus.SUCCEEDED);
        assertThat(job.resultJson()).isEqualTo("{\"ok\":true}");
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.progressPercent()).isEqualTo(100);
        assertThat(finishedEvents.of(registered.jobId())).singleElement()
            .satisfies(event -> assertThat(event.status()).isEqualTo(JobStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("재시도할 수 있는 실패가 두 번 난 뒤 성공하면 시도 3 · SUCCEEDED 다 — 중간 실패는 종료 이벤트를 내지 않는다")
    void retriesThenSucceeds() {
        long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.FLAKY, "{}", null)).jobId();

        Job job = awaitJob(jobId, finished -> finished.status() == JobStatus.SUCCEEDED);
        assertThat(job.attempt()).isEqualTo(3);
        assertThat(finishedEvents.of(jobId)).hasSize(1);
    }

    @Test
    @DisplayName("계속 실패하면 재시도 상한(3) 뒤 FAILED 로 남고 마지막 오류 코드가 남는다")
    void failsAfterRetryLimit() {
        long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.BROKEN, "{}", null)).jobId();

        Job job = awaitJob(jobId, finished -> finished.status() == JobStatus.FAILED);
        assertThat(job.attempt()).isEqualTo(3);
        assertThat(job.errorCode()).isEqualTo("UPSTREAM_DOWN");
        assertThat(finishedEvents.of(jobId)).singleElement()
            .satisfies(event -> assertThat(event.errorCode()).isEqualTo("UPSTREAM_DOWN"));
    }

    @Test
    @DisplayName("재시도할 수 없는 실패는 첫 시도에서 FAILED 다")
    void nonRetryableFailsOnFirstAttempt() {
        long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.REJECTED, "{}", null)).jobId();

        Job job = awaitJob(jobId, finished -> finished.status() == JobStatus.FAILED);
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.errorCode()).isEqualTo("BAD_INPUT");
    }

    @Test
    @DisplayName("실행 중 취소를 요청하면 heartbeat 가 알아채 핸들러가 다음 checkpoint 에서 멈추고 CANCELLED 로 끝난다")
    void cancelsRunningJob() {
        long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.SLOW, "{}", 1L)).jobId();
        awaitJob(jobId, running -> running.status() == JobStatus.RUNNING);

        requestCancel(jobId);

        Job job = awaitJob(jobId, finished -> finished.status() == JobStatus.CANCELLED);
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(finishedEvents.of(jobId)).singleElement()
            .satisfies(event -> assertThat(event.status()).isEqualTo(JobStatus.CANCELLED));
    }

    @Test
    @DisplayName("어드민 API 로 예약된 작업을 목록 · 상세에서 보고 취소하면 CANCELLED 로 끝난다 (실제 저장소 질의)")
    void adminListsAndCancels() {
        Instant later = clock.instant().plus(Duration.ofHours(1));
        long jobId = jobRegisterUseCase.register(new JobRegisterCommand(FlowHandlers.OK, "{}", null, 1L, 0, later)).jobId();

        assertThat(jobAdminUseCase.getJobs(new JobAdminSearchCommand(JobStatusFilter.QUEUED, FlowHandlers.OK, 0, 100)).contents())
            .extracting(JobAdminItem::jobId).contains(String.valueOf(jobId));
        assertThat(jobAdminUseCase.getJob(jobId).status().code()).isEqualTo("QUEUED");

        JobAdminResponse cancelled = jobAdminUseCase.cancel(jobId, 9L);

        assertThat(cancelled.status().code()).isEqualTo("CANCELLED");
        assertThat(jobRepositoryPort.findById(jobId)).hasValueSatisfying(job -> assertThat(job.status()).isEqualTo(JobStatus.CANCELLED));
        assertThat(finishedEvents.of(jobId)).hasSize(1);
    }

    @Test
    @DisplayName("다른 워커가 가져가다 죽어 lease 가 끝나면 이 워커가 다음 시도로 회수해 끝낸다 — 시도 2 · SUCCEEDED")
    void reclaimsExpiredLease() {
        Instant now = clock.instant();
        Job orphan = Job.register(snowflakeIdGenerator.generateId(), FlowHandlers.OK, "{}", null, null, 0, now, 3)
            .claim("dead-worker", now, Duration.ofMillis(300));
        jobRepositoryPort.insert(orphan);

        Job job = awaitJob(orphan.id(), finished -> finished.status() == JobStatus.SUCCEEDED);
        assertThat(job.attempt()).isEqualTo(2);
        assertThat(job.workerId()).isNotEqualTo("dead-worker");
        assertThat(finishedEvents.of(orphan.id())).hasSize(1);
    }

    /** 취소 요청은 heartbeat 와 버전이 겹치면 409(JOB_CONFLICT)다 — 다시 읽어 요청한다. */
    private void requestCancel(long jobId) {
        for (int attempt = 1; ; attempt++) {
            try {
                jobRunProcessor.requestCancel(jobId, 1L);
                return;
            } catch (JobException exception) {
                if (exception.getErrorCode() != JobErrorCode.JOB_CONFLICT || attempt >= 10) {
                    throw exception;
                }
            }
        }
    }

    private Job awaitJob(long jobId, Predicate<Job> condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        Job last = null;
        while (Instant.now().isBefore(deadline)) {
            last = jobRepositoryPort.findById(jobId).orElse(null);
            if (last != null && condition.test(last)) {
                return last;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("작업이 " + TIMEOUT + " 안에 기대 상태가 되지 않았다. jobId=" + jobId + ", last=" + last);
    }

    /** 흐름 테스트 전용 핸들러와 종료 이벤트 기록기. */
    @TestConfiguration
    static class FlowHandlers {

        static final String OK = "FLOW_OK";
        static final String FLAKY = "FLOW_FLAKY";
        static final String BROKEN = "FLOW_BROKEN";
        static final String REJECTED = "FLOW_REJECTED";
        static final String SLOW = "FLOW_SLOW";

        @Bean
        JobHandler okHandler() {
            return handler(OK, execution -> {
                execution.checkpoint(50, "절반");
                return new JobOutcome("{\"ok\":true}");
            });
        }

        @Bean
        JobHandler flakyHandler() {
            return handler(FLAKY, execution -> {
                if (execution.attempt() < 3) {
                    throw new JobExecutionException("FLAKY", true);
                }
                return JobOutcome.empty();
            });
        }

        @Bean
        JobHandler brokenHandler() {
            return handler(BROKEN, execution -> {
                throw new JobExecutionException("UPSTREAM_DOWN", true);
            });
        }

        @Bean
        JobHandler rejectedHandler() {
            return handler(REJECTED, execution -> {
                throw new JobExecutionException("BAD_INPUT", false);
            });
        }

        /** 취소될 때까지 checkpoint 를 부르며 기다린다. 10초 안에 취소되지 않으면 성공으로 끝나 테스트가 실패한다. */
        @Bean
        JobHandler slowHandler() {
            return handler(SLOW, execution -> {
                for (int tick = 0; tick < 200; tick++) {
                    execution.checkpoint(Math.min(tick, 100), null);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return JobOutcome.empty();
            });
        }

        @Bean
        FinishedEvents finishedEvents() {
            return new FinishedEvents();
        }

        private static JobHandler handler(String type, Function<JobExecution, JobOutcome> body) {
            return new JobHandler() {
                @Override
                public String type() {
                    return type;
                }

                @Override
                public JobOutcome execute(JobExecution execution) {
                    return body.apply(execution);
                }
            };
        }
    }

    /** 종료 이벤트를 모은다 — 같은 컨텍스트의 다른 테스트 작업과 섞이지 않게 작업 아이디로 거른다. */
    static class FinishedEvents {

        private final List<JobFinishedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(JobFinishedEvent event) {
            events.add(event);
        }

        List<JobFinishedEvent> of(long jobId) {
            return events.stream().filter(event -> event.jobId() == jobId).toList();
        }
    }
}
