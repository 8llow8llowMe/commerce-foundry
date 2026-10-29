package com.commercefoundry.domainlayer.aiusage.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity.AiUsageCallEntity;
import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity.AiUsageMonthEntity;
import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.repository.AiUsageCallRepository;
import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.repository.AiUsageMonthRepository;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiBudgetExceededException;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;
import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMaintenanceUseCase;
import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMeterUseCase;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageMonthRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageCallRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiBudgetPeriod;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPrice;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPriceTable.AiPriceQuote;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPricedBy;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageCall;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 원장 · 월 한도의 조건부 UPDATE 를 실제 PostgreSQL 트랜잭션 경계(READ COMMITTED · 행 잠금 · 유니크 키)로 본다. 테스트 메서드는 트랜잭션 밖이다 —
 * 스레드마다 UseCase 호출 하나가 트랜잭션이다. 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 *
 * <p>한도는 0.03 USD = 30,000 micro 로 덮는다. 기본 호출(test-small, 입력 2,000자 · 최대 출력 1,000)의 예상치는 6,000 micro — 정확히 5건만 들어간다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.hikari.maximum-pool-size=30",
    "operations.jobs.worker.enabled=false",
    "operations.ai.budget.monthly-usd=0.03"
})
class AiUsageLedgerPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static final int THREADS = 20;
    private static final long ESTIMATE = 6_000L;
    private static final long LIMIT = 30_000L;
    private static final AiPriceQuote QUOTE = new AiPriceQuote(
        new AiPrice(BigDecimal.ONE, new BigDecimal("5"), new BigDecimal("1.25"), new BigDecimal("0.1")), AiPricedBy.CONFIG, "test-1"
    );

    @Autowired
    private AiUsageMeterUseCase aiUsageMeterUseCase;

    @Autowired
    private AiUsageMaintenanceUseCase aiUsageMaintenanceUseCase;

    @Autowired
    private AiUsageCallRepositoryPort aiUsageCallRepositoryPort;

    @Autowired
    private AiUsageMonthRepositoryPort aiUsageMonthRepositoryPort;

    @Autowired
    private AiUsageCallRepository aiUsageCallRepository;

    @Autowired
    private AiUsageMonthRepository aiUsageMonthRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        aiUsageCallRepository.deleteAllInBatch();
        aiUsageMonthRepository.deleteAllInBatch();
        executor = Executors.newFixedThreadPool(THREADS);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        aiUsageCallRepository.deleteAllInBatch();
        aiUsageMonthRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("① 월 행이 없는 달에 20개가 동시에 예약하면 월 행은 하나(유니크 키 경합 재시도)이고 한도 안의 정확히 5건만 통과한다 — 첫 거절 표시는 한 번")
    void concurrentReservesNeverExceedLimit() throws Exception {
        List<Outcome> outcomes = runConcurrently(() -> aiUsageMeterUseCase.reserve(command()));

        assertThat(outcomes).filteredOn(outcome -> outcome.reservation() != null).hasSize(5);
        assertThat(outcomes).filteredOn(outcome -> outcome.exceeded() != null).hasSize(15);
        assertThat(outcomes).filteredOn(outcome -> outcome.exceeded() != null && outcome.exceeded().getAlert().exceedCrossed()).hasSize(1);
        assertThat(aiUsageMonthRepository.count()).isEqualTo(1);
        AiUsageMonthEntity month = aiUsageMonthRepository.findAll().getFirst();
        assertThat(month.getReservedMicros()).isEqualTo(LIMIT);
        assertThat(month.getLimitMicrosSnapshot()).isEqualTo(LIMIT);
        assertThat(month.getExceededAt()).isNotNull();
        assertThat(aiUsageCallRepository.count()).as("거절된 예약은 호출 행이 없다").isEqualTo(5);
    }

    @Test
    @DisplayName("② 정산은 예상치를 실측으로 옮기고 호출 행을 닫는다 — 같은 호출을 10개가 동시에 정산해도 한 번만 반영된다")
    void settleIsAppliedExactlyOnce() throws Exception {
        AiUsageReservation reservation = aiUsageMeterUseCase.reserve(command());
        AiUsageSettleCommand completed = new AiUsageSettleCommand(
            reservation.callId(), AiUsageStatus.COMPLETED, new AiTokenUsage(100, 20, 10, 100), "test-small", null, null, "msg_1", 1_234
        );

        List<AiUsageSettleResult> results = new ArrayList<>();
        for (Outcome outcome : runConcurrently(() -> aiUsageMeterUseCase.settle(completed), 10)) {
            results.add(outcome.settle());
        }

        assertThat(results).filteredOn(AiUsageSettleResult::applied).hasSize(1);
        // 100×1 + 20×5 + 10×1.25 + 100×0.1 = 222.5 → 223
        AiUsageMonthEntity month = aiUsageMonthRepository.findByPeriod(reservation.period()).orElseThrow();
        assertThat(month.getReservedMicros()).isEqualTo(223L);
        assertThat(month.getSettledMicros()).isEqualTo(223L);
        assertThat(month.getCallCount()).isEqualTo(1L);
        AiUsageCallEntity call = aiUsageCallRepository.findById(reservation.callId()).orElseThrow();
        assertThat(call.getStatus()).isEqualTo(AiUsageStatus.COMPLETED);
        assertThat(call.getCostMicros()).isEqualTo(223L);
        assertThat(call.getCostCurrency()).isEqualTo("USD");
        assertThat(call.getCacheReadInputTokens()).isEqualTo(100L);
        assertThat(call.getPricedBy()).isEqualTo(AiPricedBy.CONFIG);
        assertThat(call.getSettledAt()).isNotNull();
        assertThat(call.getElapsedMs()).isEqualTo(1_234);
    }

    @Test
    @DisplayName("③ 경고 · 초과 표시는 조건을 만족할 때 기간당 한 번만 선다 — 월 행 첫 생성은 유니크 키 경합에서 진 쪽이 false 다")
    void thresholdsAndMonthCreation() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(aiUsageMonthRepositoryPort.createIfAbsent("2026-09", LIMIT, now)).isTrue();
        assertThat(aiUsageMonthRepositoryPort.createIfAbsent("2026-09", LIMIT, now)).isFalse();
        transactionTemplate.executeWithoutResult(status -> assertThat(aiUsageMonthRepositoryPort.reserve("2026-09", 20_000L, LIMIT, now)).isTrue());

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(aiUsageMonthRepositoryPort.markWarned("2026-09", 24_000L, now)).as("아직 임계 아래").isFalse();
            assertThat(aiUsageMonthRepositoryPort.markWarned("2026-09", 20_000L, now)).isTrue();
            assertThat(aiUsageMonthRepositoryPort.markWarned("2026-09", 20_000L, now)).isFalse();
            assertThat(aiUsageMonthRepositoryPort.markExceededAtLimit("2026-09", LIMIT, now)).as("누계 20,000 < 한도").isFalse();
            assertThat(aiUsageMonthRepositoryPort.markExceededByReserve("2026-09", 6_000L, LIMIT, now)).as("지금 넣어도 26,000 — 넘지 않는다").isFalse();
            assertThat(aiUsageMonthRepositoryPort.markExceededByReserve("2026-09", 15_000L, LIMIT, now)).isTrue();
            assertThat(aiUsageMonthRepositoryPort.markExceededByReserve("2026-09", 15_000L, LIMIT, now)).isFalse();
        });

        AiUsageMonthEntity month = aiUsageMonthRepository.findByPeriod("2026-09").orElseThrow();
        assertThat(month.getWarnedAt()).isEqualTo(now);
        assertThat(month.getExceededAt()).isEqualTo(now);
        assertThat(month.getVersion()).as("예약 · 경고 · 초과가 각각 올렸다").isEqualTo(3L);
    }

    @Test
    @DisplayName("④ 멈춘 PENDING 은 ABANDONED 로 닫히고 예상치가 확정 비용이 된다. 보존 기간이 지난 닫힌 행만 지워지고 월 카운터는 남는다")
    void abandonAndRetention() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String period = AiBudgetPeriod.of(now, ZoneId.of("Asia/Seoul")).value();
        aiUsageMonthRepositoryPort.createIfAbsent(period, LIMIT, now);
        AiUsageCall stale = insertPending(1L, period, now.minus(Duration.ofHours(1)));
        AiUsageCall fresh = insertPending(2L, period, now);
        AiUsageCall expired = insertPending(3L, period, now.minus(Duration.ofDays(401)));
        AiUsageCall expiredPending = insertPending(4L, period, now.minus(Duration.ofDays(402)));
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(aiUsageCallRepositoryPort.closeIfPending(expired.settle(AiUsageStatus.FAILED, null, null, null, "UNAVAILABLE", 503, null, 10, now)))
                .isTrue();
            aiUsageMonthRepositoryPort.settle(period, ESTIMATE, 0L, 1, now);
        });

        assertThat(aiUsageMaintenanceUseCase.abandonStale()).as("1시간 · 402일 전 PENDING").isEqualTo(2);
        assertThat(aiUsageMaintenanceUseCase.abandonStale()).isZero();
        assertThat(aiUsageCallRepository.findById(stale.id()).orElseThrow().getStatus()).isEqualTo(AiUsageStatus.ABANDONED);
        assertThat(aiUsageCallRepository.findById(stale.id()).orElseThrow().getCostMicros()).isEqualTo(ESTIMATE);
        assertThat(aiUsageCallRepository.findById(fresh.id()).orElseThrow().getStatus()).isEqualTo(AiUsageStatus.PENDING);
        assertThat(aiUsageCallRepository.findById(expiredPending.id()).orElseThrow().getStatus()).isEqualTo(AiUsageStatus.ABANDONED);
        AiUsageMonthEntity month = aiUsageMonthRepository.findByPeriod(period).orElseThrow();
        // 예약 4 × 6,000, FAILED 1건이 6,000 을 풀었고, 회수 2건은 예약을 그대로 확정했다
        assertThat(month.getReservedMicros()).isEqualTo(3 * ESTIMATE);
        assertThat(month.getSettledMicros()).isEqualTo(2 * ESTIMATE);
        assertThat(month.getCallCount()).isEqualTo(3L);

        assertThat(aiUsageMaintenanceUseCase.deleteExpired()).as("400일 넘은 닫힌 행 2건(FAILED · 방금 회수한 402일 전 행)").isEqualTo(2);
        assertThat(aiUsageCallRepository.findAll()).extracting(AiUsageCallEntity::getId).containsExactlyInAnyOrder(stale.id(), fresh.id());
        assertThat(aiUsageMonthRepository.findByPeriod(period)).isPresent();
    }

    private AiUsageCall insertPending(long id, String period, Instant calledAt) {
        AiUsageCall call = AiUsageCall.pending(
            id, new AiBudgetPeriod(period), "size-chart", 1, "job:" + id, "test-small", false, 0, 2_000, ESTIMATE, QUOTE, calledAt
        );
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(aiUsageMonthRepositoryPort.reserve(period, ESTIMATE, LIMIT, calledAt)).isTrue();
            aiUsageCallRepositoryPort.insert(call);
        });
        return call;
    }

    private static AiUsageReserveCommand command() {
        return new AiUsageReserveCommand("size-chart", 1, "job:1", "test-small", 1_000, 2_000, 0, false);
    }

    private List<Outcome> runConcurrently(Callable<Object> call) throws Exception {
        return runConcurrently(call, THREADS);
    }

    /** 모든 스레드를 래치로 한꺼번에 출발시킨다. 30초 안에 끝나지 않으면(교착) 실패한다. */
    private List<Outcome> runConcurrently(Callable<Object> call, int threads) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    Object result = call.call();
                    return result instanceof AiUsageReservation reservation
                        ? new Outcome(reservation, null, null)
                        : new Outcome(null, null, (AiUsageSettleResult) result);
                } catch (AiBudgetExceededException exception) {
                    return new Outcome(null, exception, null);
                }
            }));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private record Outcome(AiUsageReservation reservation, AiBudgetExceededException exceeded, AiUsageSettleResult settle) {
    }
}
