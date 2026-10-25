package com.commercefoundry.domainlayer.job.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

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
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.global.messaging.JobRunMessage;
import com.commercefoundry.global.messaging.OutboxEntity;
import com.commercefoundry.global.messaging.OutboxRepository;
import com.commercefoundry.messaging.publish.DomainEvent;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 작업 엔진의 끝에서 끝 흐름 — 등록 · 재시도 · 만료 회수가 outbox 로 낸 실행 신호를 릴레이가 내장 Kafka 로 보내고, 실행 신호 리스너가 받아 실행 스레드 ·
 * heartbeat 스케줄러 · H2 저장소로 처리한다(test 프로파일의 짧은 lease · 백오프). 내장 Kafka(KRaft)라 Docker 없이 돈다 — messaging-core 통합 테스트와 같다.
 *
 * <p>#212 · #307 완료 기준: 등록은 바로 작업 아이디를 돌려주고 워커가 처리한 뒤 완료 · 실패로 바뀐다, 실패는 재시도 상한 뒤 FAILED 로 남는다,
 * 중복 신호는 한 번만 실행한다, 워커가 죽은 작업은 만료 판정이 다시 신호를 내 회수한다, 읽을 수 없는 신호 · 모르는 종류는 DLT 로 간다.
 * 여러 인스턴스의 동시 가져가기는 {@code JobClaimConcurrencyTest}(PostgreSQL)가 본다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:operations-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "commerce.messaging.enabled=true",
    "commerce.messaging.namespace=commerce-foundry.test",
    "commerce.messaging.outbox.relay.interval=50ms",
    "commerce.messaging.retry.attempts=2",
    "commerce.messaging.retry.initial-backoff=10ms",
    "commerce.messaging.retry.max-backoff=50ms"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers", topics = {
    JobWorkerFlowTest.TOPIC, JobWorkerFlowTest.TOPIC + ".dlt"
})
class JobWorkerFlowTest {

    static final String TOPIC = "commerce-foundry.test.operations.job-run";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private JobRegisterUseCase jobRegisterUseCase;

    @Autowired
    private JobAdminUseCase jobAdminUseCase;

    @Autowired
    private JobRepositoryPort jobRepositoryPort;

    @Autowired
    private JobRunProcessor jobRunProcessor;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    private Clock clock;

    @Autowired
    private FinishedEvents finishedEvents;

    @Autowired
    private FlowHandlers.Executions executions;

    @Autowired
    private DeadLetters deadLetters;

    @Test
    @DisplayName("등록은 바로 QUEUED 작업 아이디를 돌려주고, 실행 신호를 받은 워커가 처리하면 SUCCEEDED · 결과 JSON · 시도 1 이 되고 종료 이벤트가 한 번 나간다")
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
    @DisplayName("재시도할 수 있는 실패가 두 번 난 뒤 성공하면 시도 3 · SUCCEEDED 다 — 재시도마다 실행 신호가 다시 나가고, 중간 실패는 종료 이벤트를 내지 않는다")
    void retriesThenSucceeds() {
        long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.FLAKY, "{}", null)).jobId();

        Job job = awaitJob(jobId, finished -> finished.status() == JobStatus.SUCCEEDED);
        assertThat(job.attempt()).isEqualTo(3);
        assertThat(finishedEvents.of(jobId)).hasSize(1);
        assertThat(signalsOf(jobId)).as("등록 1 + 재시도 2").hasSize(3);
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
    @DisplayName("어드민 API 로 예약된 작업을 목록 · 상세에서 보고 취소하면 CANCELLED 로 끝난다 — 예약 시각 전 실행 신호는 outbox 에서 기다린다")
    void adminListsAndCancels() {
        Instant later = clock.instant().plus(Duration.ofHours(1));
        long jobId = jobRegisterUseCase.register(new JobRegisterCommand(FlowHandlers.OK, "{}", null, 1L, 0, later)).jobId();

        Instant scheduledAt = jobRepositoryPort.findById(jobId).orElseThrow().scheduledAt();
        assertThat(scheduledAt).isCloseTo(later, within(1, ChronoUnit.MILLIS));
        assertThat(signalsOf(jobId)).singleElement().satisfies(signal -> {
            assertThat(signal.getAvailableAt()).isEqualTo(scheduledAt);
            assertThat(signal.getPublishedAt()).isNull();
        });
        assertThat(jobAdminUseCase.getJobs(new JobAdminSearchCommand(JobStatusFilter.QUEUED, FlowHandlers.OK, 0, 100)).contents())
            .extracting(JobAdminItem::jobId).contains(String.valueOf(jobId));
        assertThat(jobAdminUseCase.getJob(jobId).status().code()).isEqualTo("QUEUED");

        JobAdminResponse cancelled = jobAdminUseCase.cancel(jobId, 9L);

        assertThat(cancelled.status().code()).isEqualTo("CANCELLED");
        assertThat(jobRepositoryPort.findById(jobId)).hasValueSatisfying(job -> assertThat(job.status()).isEqualTo(JobStatus.CANCELLED));
        assertThat(finishedEvents.of(jobId)).hasSize(1);
    }

    @Test
    @DisplayName("다른 워커가 가져가다 죽어 lease 가 끝나면 만료 판정이 QUEUED 로 돌리고 실행 신호를 다시 내 회수한다 — 시도 2 · SUCCEEDED")
    void reclaimsExpiredLease() {
        Instant now = clock.instant();
        Job orphan = Job.register(snowflakeIdGenerator.generateId(), FlowHandlers.OK, "{}", null, null, 0, now, 3)
            .claim("dead-worker", now, Duration.ofMillis(300));
        jobRepositoryPort.insert(orphan);

        Job job = awaitJob(orphan.id(), finished -> finished.status() == JobStatus.SUCCEEDED);
        assertThat(job.attempt()).isEqualTo(2);
        assertThat(job.workerId()).isNotEqualTo("dead-worker");
        assertThat(job.errorCode()).isNull();
        assertThat(finishedEvents.of(orphan.id())).hasSize(1);
        assertThat(signalsOf(orphan.id())).as("만료 판정이 낸 신호").hasSize(1);
    }

    @Test
    @DisplayName("같은 실행 신호가 여러 번 와도 한 번만 실행한다 — 이미 가져간 시도의 신호는 버린다")
    void duplicateSignalsRunOnce() {
        long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.COUNTED, "{}", null)).jobId();
        awaitJob(jobId, finished -> finished.status() == JobStatus.SUCCEEDED);
        String signal = signalsOf(jobId).getFirst().getPayload();

        kafkaTemplate.send(TOPIC, String.valueOf(jobId), signal);
        kafkaTemplate.send(TOPIC, String.valueOf(jobId), signal);
        // 파티션 하나 · 컨슈머 스레드 하나라 뒤 신호가 처리되면 앞의 중복 신호도 처리가 끝났다
        long marker = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.OK, "{}", null)).jobId();
        awaitJob(marker, finished -> finished.status() == JobStatus.SUCCEEDED);

        assertThat(executions.count(jobId)).isEqualTo(1);
        assertThat(finishedEvents.of(jobId)).hasSize(1);
        assertThat(jobRepositoryPort.findById(jobId)).hasValueSatisfying(job -> assertThat(job.attempt()).isEqualTo(1));
    }

    @Test
    @DisplayName("인스턴스당 동시 실행 1 인 종류는 칸이 찰 때 같은 시도의 신호를 outbox 로 다시 내(DEFERRED) 차례로 실행한다")
    void perTypeLimitDefersSerially() {
        long first = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.SERIAL, "{}", null)).jobId();
        long second = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.SERIAL, "{}", null)).jobId();

        awaitJob(first, finished -> finished.status() == JobStatus.SUCCEEDED);
        awaitJob(second, finished -> finished.status() == JobStatus.SUCCEEDED);
        assertThat(executions.maxConcurrent(FlowHandlers.SERIAL)).isEqualTo(1);
        assertThat(executions.count(first)).isEqualTo(1);
        assertThat(executions.count(second)).isEqualTo(1);
    }

    @Test
    @DisplayName("읽을 수 없는 신호는 재시도 없이 DLT 로 가고, 리스너는 다음 신호를 계속 처리한다")
    void unreadableSignalGoesToDeadLetter() {
        kafkaTemplate.send(TOPIC, "garbage", "not-json");

        await().atMost(TIMEOUT).until(() -> deadLetters.received().contains("not-json"));
        long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(FlowHandlers.OK, "{}", null)).jobId();
        awaitJob(jobId, finished -> finished.status() == JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("이 인스턴스에 핸들러가 없는 종류의 신호는 재시도 없이 DLT 로 간다")
    void unknownTypeGoesToDeadLetter() {
        long jobId = snowflakeIdGenerator.generateId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publish(new DomainEvent(
            JobRunMessage.AGGREGATE_TYPE, String.valueOf(jobId), JobRunMessage.EVENT_TYPE, JobRunMessage.SCHEMA_VERSION,
            new JobRunMessage(String.valueOf(jobId), "FLOW_NOT_REGISTERED", 0, clock.instant())
        )));

        await().atMost(TIMEOUT).until(() -> deadLetters.received().stream().anyMatch(value -> value.contains("FLOW_NOT_REGISTERED")));
    }

    /** 이 작업의 실행 신호 outbox 행 — 아이디 오름차순. */
    private List<OutboxEntity> signalsOf(long jobId) {
        return outboxRepository.findAll().stream()
            .filter(row -> row.getAggregateId().equals(String.valueOf(jobId)))
            .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
            .toList();
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

    /** 흐름 테스트 전용 핸들러 · 종료 이벤트 기록기 · DLT 수신기. */
    @TestConfiguration
    static class FlowHandlers {

        static final String OK = "FLOW_OK";
        static final String FLAKY = "FLOW_FLAKY";
        static final String BROKEN = "FLOW_BROKEN";
        static final String REJECTED = "FLOW_REJECTED";
        static final String SLOW = "FLOW_SLOW";
        static final String COUNTED = "FLOW_COUNTED";
        static final String SERIAL = "FLOW_SERIAL";

        @Bean
        Executions executions() {
            return new Executions();
        }

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
                    sleep(50);
                }
                return JobOutcome.empty();
            });
        }

        @Bean
        JobHandler countedHandler(Executions executions) {
            return handler(COUNTED, execution -> {
                executions.started(COUNTED, execution.jobId());
                executions.finished(COUNTED);
                return JobOutcome.empty();
            });
        }

        @Bean
        JobHandler serialHandler(Executions executions) {
            return new JobHandler() {
                @Override
                public String type() {
                    return SERIAL;
                }

                @Override
                public JobHandlerOptions options() {
                    return new JobHandlerOptions(null, null, null, 1);
                }

                @Override
                public JobOutcome execute(JobExecution execution) {
                    executions.started(SERIAL, execution.jobId());
                    try {
                        sleep(300);
                        return JobOutcome.empty();
                    } finally {
                        executions.finished(SERIAL);
                    }
                }
            };
        }

        @Bean
        FinishedEvents finishedEvents() {
            return new FinishedEvents();
        }

        @Bean
        DeadLetters deadLetters() {
            return new DeadLetters();
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

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        /** 작업별 실행 횟수와 종류별 최대 동시 실행 수. */
        static class Executions {

            private final Map<Long, AtomicInteger> counts = new ConcurrentHashMap<>();
            private final Map<String, AtomicInteger> running = new ConcurrentHashMap<>();
            private final Map<String, AtomicInteger> maxRunning = new ConcurrentHashMap<>();

            void started(String type, long jobId) {
                counts.computeIfAbsent(jobId, key -> new AtomicInteger()).incrementAndGet();
                int now = running.computeIfAbsent(type, key -> new AtomicInteger()).incrementAndGet();
                maxRunning.computeIfAbsent(type, key -> new AtomicInteger()).accumulateAndGet(now, Math::max);
            }

            void finished(String type) {
                running.get(type).decrementAndGet();
            }

            int count(long jobId) {
                AtomicInteger count = counts.get(jobId);
                return count == null ? 0 : count.get();
            }

            int maxConcurrent(String type) {
                AtomicInteger max = maxRunning.get(type);
                return max == null ? 0 : max.get();
            }
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

    /** 실행 신호 DLT 를 읽는다 — 그룹도 이 환경 namespace 로 시작해야 공통 리스너 검사를 통과한다. */
    static class DeadLetters {

        private final List<String> values = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = TOPIC + ".dlt", groupId = "commerce-foundry.test.operations.flow-test-dlt-probe")
        void on(String value) {
            values.add(value);
        }

        List<String> received() {
            return values;
        }
    }
}
