package com.commercefoundry.domainlayer.job.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobEntity;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.mapper.JobMapperImpl;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.query.ExpiredJobLeaseQueryResult;
import com.commercefoundry.domainlayer.job.application.port.out.query.JobSummaryQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.domain.model.RetryPolicy;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 작업의 저장 · merge(버전 검사) · 멱등 키 유니크 키 · 리스 만료 후보 조회 · 조건부 UPDATE(heartbeat · 취소 요청)를 실제 PostgreSQL 에 질의해 본다
 * ({@code coding-conventions.md} §9-5). 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 *
 * <p>여러 워커의 동시 가져가기는 실제 Processor 트랜잭션 경계로 {@code JobClaimConcurrencyTest} 가 본다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, OperationsServiceFeaturesConfig.class, JobRepositoryAdapter.class, JobMapperImpl.class})
class JobPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String WORKER = "operations-service:host-a:100:aaaaaaaa";
    private static final String OTHER_WORKER = "operations-service:host-b:200:bbbbbbbb";
    private static final RetryPolicy RETRY = new RetryPolicy(Duration.ofSeconds(30), 4, Duration.ofMinutes(30), 0.0);

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JobRepositoryPort jobRepositoryPort;

    @Test
    @DisplayName("저장: 새 작업은 버전 0 으로 INSERT 되고, 활성 멱등 키 칸은 멱등 키와 같다 — 끝나면 merge 가 그 칸을 비운다")
    void insertAndFinish() {
        Job saved = jobRepositoryPort.insert(queued(1L, "REPORT", 0, NOW, "REPORT@2026-09"));
        entityManager.clear();

        assertThat(saved.version()).isZero();
        assertThat(entityManager.find(JobEntity.class, 1L).getActiveDedupKey()).isEqualTo("REPORT@2026-09");
        assertThat(jobRepositoryPort.findActiveByDedupKey("REPORT", "REPORT@2026-09")).hasValueSatisfying(job -> assertThat(job.id()).isEqualTo(1L));

        Job running = jobRepositoryPort.update(saved.claim(WORKER, NOW, LEASE));
        jobRepositoryPort.update(running.succeed("{\"rows\":1}", NOW.plusSeconds(10)));
        entityManager.clear();

        JobEntity finished = entityManager.find(JobEntity.class, 1L);
        assertThat(finished.getVersion()).isEqualTo(2L);
        assertThat(finished.getActiveDedupKey()).isNull();
        assertThat(finished.getDedupKey()).isEqualTo("REPORT@2026-09");
        assertThat(finished.getResultJson()).isEqualTo("{\"rows\":1}");
        assertThat(jobRepositoryPort.findActiveByDedupKey("REPORT", "REPORT@2026-09")).isEmpty();
    }

    @Test
    @DisplayName("정기 슬롯 확인: 끝난(SUCCEEDED) 작업도 같은 종류 · 같은 키로 등록된 적이 있으면 true 다 — 활성 작업 조회에는 걸리지 않는다")
    void existsByDedupKeyIncludesFinishedJobs() {
        Job saved = jobRepositoryPort.insert(queued(51L, "REPORT", 0, NOW, "planner:DAILY:Asia/Seoul:2026-10-24T09:00:00"));
        Job running = jobRepositoryPort.update(saved.claim(WORKER, NOW, LEASE));
        jobRepositoryPort.update(running.succeed("{}", NOW.plusSeconds(10)));
        entityManager.clear();

        assertThat(jobRepositoryPort.existsByDedupKey("REPORT", "planner:DAILY:Asia/Seoul:2026-10-24T09:00:00")).isTrue();
        assertThat(jobRepositoryPort.findActiveByDedupKey("REPORT", "planner:DAILY:Asia/Seoul:2026-10-24T09:00:00")).isEmpty();
        assertThat(jobRepositoryPort.existsByDedupKey("OTHER", "planner:DAILY:Asia/Seoul:2026-10-24T09:00:00")).isFalse();
    }

    @Test
    @DisplayName("리스 만료 후보: lease 가 지난 RUNNING 만 (lease 만료 시각 → 아이디) 순으로 limit 건씩, 마지막 행 다음부터 이어 읽는다")
    void findExpiredLeasesKeyset() {
        // lease 가 지난 RUNNING — 16 · 19 는 같은 시각에 가져갔다
        jobRepositoryPort.update(jobRepositoryPort.insert(queued(16L, "REPORT", 0, NOW.minusSeconds(900), null)).claim(WORKER, NOW.minusSeconds(900), LEASE));
        jobRepositoryPort.update(jobRepositoryPort.insert(queued(19L, "OTHER", 0, NOW.minusSeconds(900), null)).claim(WORKER, NOW.minusSeconds(900), LEASE));
        jobRepositoryPort.update(jobRepositoryPort.insert(queued(15L, "REPORT", 0, NOW.minusSeconds(600), null)).claim(WORKER, NOW.minusSeconds(600), LEASE));
        // 유효한 RUNNING · 예약 시각이 지난 QUEUED · 끝난 작업은 후보가 아니다
        jobRepositoryPort.update(jobRepositoryPort.insert(queued(17L, "REPORT", 9, NOW.minusSeconds(60), null)).claim(WORKER, NOW.minusSeconds(60), LEASE));
        jobRepositoryPort.insert(queued(11L, "REPORT", 0, NOW.minusSeconds(600), null));
        Job running = jobRepositoryPort.update(jobRepositoryPort.insert(queued(18L, "REPORT", 9, NOW.minusSeconds(900), null)).claim(WORKER, NOW.minusSeconds(900), LEASE));
        jobRepositoryPort.update(running.fail("E", null, false, NOW.minusSeconds(800), RETRY));
        entityManager.clear();

        List<ExpiredJobLeaseQueryResult> all = jobRepositoryPort.findExpiredLeases(NOW, null, 10);

        assertThat(all).extracting(ExpiredJobLeaseQueryResult::jobId).containsExactly(16L, 19L, 15L);
        assertThat(all.getFirst().leaseExpiresAt()).isEqualTo(NOW.minusSeconds(900).plus(LEASE));

        List<ExpiredJobLeaseQueryResult> first = jobRepositoryPort.findExpiredLeases(NOW, null, 1);
        assertThat(first).extracting(ExpiredJobLeaseQueryResult::jobId).containsExactly(16L);
        assertThat(jobRepositoryPort.findExpiredLeases(NOW, first.getFirst(), 1)).as("같은 lease 시각 안에서는 아이디로 잇는다")
            .extracting(ExpiredJobLeaseQueryResult::jobId).containsExactly(19L);
        assertThat(jobRepositoryPort.findExpiredLeases(NOW, all.get(1), 10)).extracting(ExpiredJobLeaseQueryResult::jobId).containsExactly(15L);
        assertThat(jobRepositoryPort.findExpiredLeases(NOW, all.getLast(), 10)).isEmpty();
    }

    @Test
    @DisplayName("어드민 목록: 아이디 내림차순, 상태 · 종류 조건, 전체 건수는 자르기 전 개수, 마지막 페이지를 넘기면 빈 목록이다")
    void search() {
        jobRepositoryPort.insert(queued(31L, "REPORT", 0, NOW, null));
        jobRepositoryPort.insert(queued(32L, "OTHER", 0, NOW, null));
        jobRepositoryPort.update(jobRepositoryPort.insert(queued(33L, "REPORT", 0, NOW, null)).claim(WORKER, NOW, LEASE));
        jobRepositoryPort.insert(queued(34L, "REPORT", 0, NOW, null));
        entityManager.clear();

        Page<JobSummaryQueryResult> all = jobRepositoryPort.findSummaryPage(null, null, 0, 3);

        assertThat(all.getContent()).extracting(JobSummaryQueryResult::jobId).containsExactly(34L, 33L, 32L);
        assertThat(all.getContent()).allSatisfy(summary -> assertThat(summary.requestedAt()).isNotNull());
        assertThat(all.getTotalElements()).isEqualTo(4);
        assertThat(jobRepositoryPort.findSummaryPage(JobStatus.QUEUED, null, 0, 10).getContent())
            .extracting(JobSummaryQueryResult::jobId).containsExactly(34L, 32L, 31L);
        assertThat(jobRepositoryPort.findSummaryPage(null, "REPORT", 0, 10).getContent())
            .extracting(JobSummaryQueryResult::jobId).containsExactly(34L, 33L, 31L);
        assertThat(jobRepositoryPort.findSummaryPage(JobStatus.RUNNING, "REPORT", 0, 10).getContent())
            .extracting(JobSummaryQueryResult::jobId).containsExactly(33L);
        assertThat(jobRepositoryPort.findSummary(33L)).hasValueSatisfying(summary -> assertThat(summary.status()).isEqualTo(JobStatus.RUNNING));
        Page<JobSummaryQueryResult> beyond = jobRepositoryPort.findSummaryPage(null, null, 5, 3);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("heartbeat: 이 워커의 이 시도면 lease · 진행률을 쓰고 버전을 올린다. 다른 워커 · 다른 시도 · 취소 요청됨 · 끝난 작업은 0행이다")
    void heartbeat() {
        Job running = jobRepositoryPort.update(jobRepositoryPort.insert(queued(21L, "REPORT", 0, NOW, null)).claim(WORKER, NOW, LEASE));
        Instant extended = NOW.plus(LEASE).plusSeconds(60);

        assertThat(jobRepositoryPort.heartbeat(21L, WORKER, 1, 40, "40% 처리", extended, NOW.plusSeconds(60))).isEqualTo(1);
        assertThat(jobRepositoryPort.heartbeat(21L, OTHER_WORKER, 1, 50, null, extended, NOW)).as("다른 워커").isZero();
        assertThat(jobRepositoryPort.heartbeat(21L, WORKER, 2, 50, null, extended, NOW)).as("다른 시도").isZero();

        Job afterHeartbeat = jobRepositoryPort.findById(21L).orElseThrow();
        assertThat(afterHeartbeat.leaseExpiresAt()).isEqualTo(extended);
        assertThat(afterHeartbeat.progressPercent()).isEqualTo(40);
        assertThat(afterHeartbeat.progressMessage()).isEqualTo("40% 처리");
        assertThat(afterHeartbeat.version()).isEqualTo(running.version() + 1);

        assertThat(jobRepositoryPort.requestCancel(21L, 7L, NOW)).isEqualTo(1);
        assertThat(jobRepositoryPort.requestCancel(21L, 8L, NOW)).as("이미 요청됨 — 멱등").isZero();
        assertThat(jobRepositoryPort.heartbeat(21L, WORKER, 1, 60, null, extended, NOW)).as("취소 요청됨 — 워커가 다시 읽어 멈춘다").isZero();
        Job flagged = jobRepositoryPort.findById(21L).orElseThrow();
        assertThat(flagged.cancelRequested()).isTrue();
        assertThat(flagged.cancelRequestedByMemberId()).isEqualTo(7L);
        assertThat(flagged.version()).isEqualTo(running.version() + 2);

        jobRepositoryPort.update(flagged.cancel(NOW));
        assertThat(jobRepositoryPort.heartbeat(21L, WORKER, 1, 60, null, extended, NOW)).as("끝난 작업").isZero();
        assertThat(jobRepositoryPort.requestCancel(21L, 7L, NOW)).as("끝난 작업").isZero();
    }

    @Test
    @DisplayName("merge 버전 검사: heartbeat 가 버전을 올린 뒤 옛 버전으로 저장하면 JOB_CONFLICT — 옛 lease 로 만료 판정한 쪽이 진다")
    void staleUpdateConflicts() {
        Job running = jobRepositoryPort.update(jobRepositoryPort.insert(queued(31L, "REPORT", 0, NOW, null)).claim(WORKER, NOW, LEASE));
        jobRepositoryPort.heartbeat(31L, WORKER, 1, 10, null, NOW.plus(LEASE).plusSeconds(60), NOW.plusSeconds(60));

        Job staleReclaim = running.expireLease(NOW.plus(LEASE).plusSeconds(1));

        assertThatThrownBy(() -> jobRepositoryPort.update(staleReclaim))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.JOB_CONFLICT));
    }

    @Test
    @DisplayName("멱등 키 유니크 키: 끝난 작업의 키 · 다른 종류의 같은 키는 쓸 수 있고, 같은 종류 · 같은 키의 활성 작업을 또 INSERT 하면 JOB_DEDUP_CONFLICT 다 (제약 이름으로 가린다)")
    void activeDedupKeyIsUnique() {
        Job first = jobRepositoryPort.insert(queued(41L, "REPORT", 0, NOW, "KEY"));
        jobRepositoryPort.update(first.requestCancel(null, NOW));
        jobRepositoryPort.insert(queued(42L, "REPORT", 0, NOW, "KEY"));
        jobRepositoryPort.insert(queued(44L, "SETTLEMENT", 0, NOW, "KEY"));
        assertThat(jobRepositoryPort.findActiveByDedupKey("SETTLEMENT", "KEY")).hasValueSatisfying(job -> assertThat(job.id()).isEqualTo(44L));

        // PostgreSQL 은 위반 뒤 트랜잭션을 중단시킨다 — 이 테스트의 마지막 문장이다
        assertThatThrownBy(() -> jobRepositoryPort.insert(queued(43L, "REPORT", 0, NOW, "KEY")))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.JOB_DEDUP_CONFLICT));
    }

    private static Job queued(long id, String type, int priority, Instant scheduledAt, String dedupKey) {
        return Job.register(id, type, "{}", dedupKey, null, priority, scheduledAt, 3);
    }
}
