package com.commercefoundry.domainlayer.job.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.repository.JobRepository;
import com.commercefoundry.domainlayer.job.application.command.JobRegisterCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobRegistrationInfo;
import com.commercefoundry.domainlayer.job.application.port.in.JobRegisterUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobClaimProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.JobFixtures;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 여러 워커의 동시 가져가기 · 같은 멱등 키의 동시 등록 — 실제 Processor 트랜잭션 경계(프록시 · READ COMMITTED · 행 잠금 · {@code @Version})로 PostgreSQL 에서 본다.
 * 테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본) — 스레드마다 호출 하나가 트랜잭션 하나다.
 *
 * <p>스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. 커넥션 풀은 동시 스레드 수보다 크게 둔다 — 풀이 작으면 경합이 풀 대기로 바뀌어 행 경합을 보지 못한다.
 * 워커 폴링은 끈다 — 이 테스트가 직접 가져간다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.hikari.maximum-pool-size=30",
    "operations.jobs.worker.enabled=false"
})
class JobClaimConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static final String TYPE = "CONCURRENCY_TEST";
    private static final int THREADS = 20;
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private JobRegisterUseCase jobRegisterUseCase;

    @Autowired
    private JobClaimProcessor jobClaimProcessor;

    @Autowired
    private JobRepository jobRepository;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAllInBatch();
        executor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jobRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("① 워커 20개가 같은 작업을 동시에 가져가면 정확히 하나만 RUNNING 으로 가져가고, 나머지는 빈 값이거나 버전 충돌(JOB_CONFLICT)이다")
    void exactlyOneWorkerClaims() throws Exception {
        for (int round = 0; round < 5; round++) {
            long jobId = jobRegisterUseCase.register(JobRegisterCommand.now(TYPE, "{}", null)).jobId();

            List<Outcome<Optional<Job>>> outcomes = runConcurrently(index -> jobClaimProcessor.claim(jobId, "worker-" + index, LEASE));

            List<Job> claimed = outcomes.stream().map(Outcome::result).filter(result -> result != null && result.isPresent()).map(Optional::get).toList();
            assertThat(claimed).as("가져간 워커").hasSize(1);
            assertThat(outcomes).allMatch(outcome -> outcome.errorCode() == null || outcome.errorCode() == JobErrorCode.JOB_CONFLICT);
            var stored = jobRepository.findById(jobId).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(stored.getAttempt()).as("시도는 한 번만 늘었다").isEqualTo(1);
            assertThat(stored.getWorkerId()).isEqualTo(claimed.getFirst().workerId());
        }
    }

    @Test
    @DisplayName("② 같은 멱등 키로 20개가 동시에 등록하면 행은 하나이고, 모두 같은 작업 아이디를 받는다 — 하나만 created 다")
    void sameDedupKeyRegistersOnce() throws Exception {
        List<Outcome<JobRegistrationInfo>> outcomes = runConcurrently(
            index -> jobRegisterUseCase.register(new JobRegisterCommand(TYPE, "{}", TYPE + "@2026-10-24T00:00:00Z", null, 0, null))
        );

        assertThat(outcomes).allMatch(outcome -> outcome.errorCode() == null);
        assertThat(outcomes).extracting(outcome -> outcome.result().jobId()).containsOnly(outcomes.getFirst().result().jobId());
        assertThat(outcomes).filteredOn(outcome -> outcome.result().created()).hasSize(1);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    /** 모든 스레드를 래치로 한꺼번에 출발시킨다. 30초 안에 끝나지 않으면(교착) 실패한다. */
    private <T> List<Outcome<T>> runConcurrently(IndexedCall<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        for (int index = 0; index < THREADS; index++) {
            int current = index;
            Callable<Outcome<T>> task = () -> {
                start.await();
                try {
                    return new Outcome<>(call.run(current), null);
                } catch (JobException exception) {
                    return new Outcome<>(null, exception.getErrorCode());
                }
            };
            futures.add(executor.submit(task));
        }
        start.countDown();
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<Outcome<T>> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    @FunctionalInterface
    private interface IndexedCall<T> {

        T run(int index);
    }

    /** @param errorCode 성공이면 null */
    private record Outcome<T>(T result, JobErrorCode errorCode) {
    }

    @TestConfiguration
    static class ConcurrencyTestHandlerConfig {

        @Bean
        JobHandler concurrencyTestHandler() {
            return JobFixtures.handler(TYPE);
        }
    }
}
