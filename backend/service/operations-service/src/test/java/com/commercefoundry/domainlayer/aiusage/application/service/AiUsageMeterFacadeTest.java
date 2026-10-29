package com.commercefoundry.domainlayer.aiusage.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiBudgetExceededException;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageErrorCode;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageException;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageMonthRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageCallRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.application.port.out.query.AiUsageMonthQueryResult;
import com.commercefoundry.domainlayer.aiusage.application.service.processor.AiBudgetProcessor;
import com.commercefoundry.domainlayer.aiusage.application.service.processor.AiUsageMaintenanceProcessor;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageCall;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import com.commercefoundry.global.properties.AiUsageProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 예약 · 정산 · 임계 · 회수 · 보존의 규칙을 가짜 포트로 본다. 가짜 월 포트는 조건부 UPDATE 와 같은 조건을 건다 — 실제 SQL · 동시성은 {@code AiUsageLedgerPostgresTest}.
 *
 * <p>단가 test-small = 입력 1 · 출력 5 USD/MTok. 한도 0.01 USD = 10,000 micro, 경고 8,000. 기본 호출(입력 2,000자 · 최대 출력 1,000)의 예상치 =
 * 1,000 × 1 + 1,000 × 5 = 6,000 micro.
 */
class AiUsageMeterFacadeTest {

    private static final Instant T0 = Instant.parse("2026-10-29T01:00:00Z");
    private static final String PERIOD = "2026-10";
    private static final long ESTIMATE = 6_000L;

    private final MutableClock clock = new MutableClock(T0);
    private final FakeCallPort calls = new FakeCallPort();
    private final FakeMonthPort months = new FakeMonthPort();

    @Test
    @DisplayName("그 달 첫 예약은 월 행을 만든 뒤 예약하고, 호출 행을 PENDING 으로 남긴다")
    void firstReserveOfMonthCreatesMonth() {
        AiUsageReservation reservation = meter(properties("0.01")).reserve(command(2_000, 1_000));

        assertThat(reservation.period()).isEqualTo(PERIOD);
        assertThat(reservation.estimateCostMicros()).isEqualTo(ESTIMATE);
        assertThat(months.get(PERIOD).reserved).isEqualTo(ESTIMATE);
        assertThat(months.get(PERIOD).limit).isEqualTo(10_000L);
        AiUsageCall call = calls.rows.get(reservation.callId());
        assertThat(call.status()).isEqualTo(AiUsageStatus.PENDING);
        assertThat(call.calledAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("한도를 넘는 예약은 BUDGET_EXCEEDED 로 즉시 실패하고 호출 행을 남기지 않는다 — 이 달 첫 거절만 exceedCrossed 다")
    void exceedingReserveFailsAndFlagsOnce() {
        AiUsageMeterFacade meter = meter(properties("0.01"));
        meter.reserve(command(2_000, 1_000));

        assertThatThrownBy(() -> meter.reserve(command(2_000, 1_000)))
            .isInstanceOfSatisfying(AiBudgetExceededException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(AiUsageErrorCode.BUDGET_EXCEEDED);
                assertThat(exception.getAlert().exceedCrossed()).isTrue();
                assertThat(exception.getAlert().reservedMicros()).isEqualTo(ESTIMATE);
                assertThat(exception.getAlert().limitMicros()).isEqualTo(10_000L);
                assertThat(exception.getEstimateCostMicros()).isEqualTo(ESTIMATE);
            });
        assertThatThrownBy(() -> meter.reserve(command(2_000, 1_000)))
            .isInstanceOfSatisfying(AiBudgetExceededException.class, exception -> assertThat(exception.getAlert().exceedCrossed()).isFalse());
        assertThat(calls.rows).hasSize(1);
        assertThat(months.get(PERIOD).reserved).isEqualTo(ESTIMATE);
    }

    @ParameterizedTest(name = "{0} → 비용 {1}, 호출 수 {2}")
    @CsvSource({"COMPLETED, 200, 1", "UNUSABLE, 200, 1", "FAILED, 0, 1", "NOT_SENT, 0, 0", "TIMEOUT, 6000, 1"})
    @DisplayName("정산은 예상치를 확정 비용으로 옮긴다 — 실측 · 0 · 예상치 규칙과 호출 수 규칙")
    void settleMovesEstimateToActual(AiUsageStatus outcome, long expectedCost, long expectedCalls) {
        AiUsageMeterFacade meter = meter(properties("0.01"));
        AiUsageReservation reservation = meter.reserve(command(2_000, 1_000));

        AiUsageSettleResult result = meter.settle(settle(reservation.callId(), outcome, new AiTokenUsage(100, 20, 0, 0)));

        assertThat(result.applied()).isTrue();
        assertThat(result.status()).isEqualTo(outcome);
        assertThat(result.costMicros()).isEqualTo(expectedCost);
        assertThat(result.alert()).isNull();
        Month month = months.get(PERIOD);
        assertThat(month.reserved).isEqualTo(expectedCost);
        assertThat(month.settled).isEqualTo(expectedCost);
        assertThat(month.calls).isEqualTo(expectedCalls);
    }

    @Test
    @DisplayName("같은 호출을 두 번 정산해도 한 번만 반영된다(멱등) — 없는 호출도 아무것도 바꾸지 않는다")
    void settleIsIdempotent() {
        AiUsageMeterFacade meter = meter(properties("0.01"));
        AiUsageReservation reservation = meter.reserve(command(2_000, 1_000));
        AiUsageSettleCommand completed = settle(reservation.callId(), AiUsageStatus.COMPLETED, new AiTokenUsage(100, 20, 0, 0));

        assertThat(meter.settle(completed).applied()).isTrue();
        assertThat(meter.settle(completed)).isEqualTo(AiUsageSettleResult.notApplied());
        assertThat(meter.settle(settle(reservation.callId(), AiUsageStatus.TIMEOUT, null)).applied()).isFalse();
        assertThat(meter.settle(settle(999L, AiUsageStatus.TIMEOUT, null)).applied()).isFalse();

        Month month = months.get(PERIOD);
        assertThat(month.reserved).isEqualTo(200L);
        assertThat(month.settled).isEqualTo(200L);
        assertThat(month.calls).isEqualTo(1L);
    }

    @Test
    @DisplayName("경고 임계(한도의 80%)는 처음 넘은 정산에서 한 번만 알린다")
    void warnsOncePerPeriod() {
        AiUsageMeterFacade meter = meter(properties("0.01"));
        // 입력 1,000 + 출력 1,500 × 5 = 8,500 — 경고 임계 8,000 이상, 한도 10,000 이하
        AiUsageReservation first = meter.reserve(command(2_000, 1_500));

        AiUsageSettleResult crossed = meter.settle(settle(first.callId(), AiUsageStatus.TIMEOUT, null));

        assertThat(crossed.alert()).isNotNull();
        assertThat(crossed.alert().warnCrossed()).isTrue();
        assertThat(crossed.alert().exceedCrossed()).isFalse();
        assertThat(crossed.alert().reservedMicros()).isEqualTo(8_500L);

        AiUsageReservation second = meter.reserve(command(2, 100));
        assertThat(meter.settle(settle(second.callId(), AiUsageStatus.TIMEOUT, null)).alert()).isNull();
        assertThat(months.get(PERIOD).warnedAt).isEqualTo(T0);
    }

    @Test
    @DisplayName("실측이 예상치보다 커서 정산 뒤 누계가 한도 이상이면 초과도 한 번 알린다")
    void settleOverLimitFlagsExceeded() {
        AiUsageMeterFacade meter = meter(properties("0.01"));
        AiUsageReservation reservation = meter.reserve(command(2_000, 1_000));

        AiUsageSettleResult result = meter.settle(settle(reservation.callId(), AiUsageStatus.COMPLETED, new AiTokenUsage(0, 2_400, 0, 0)));

        assertThat(result.costMicros()).isEqualTo(12_000L);
        assertThat(result.alert().warnCrossed()).isTrue();
        assertThat(result.alert().exceedCrossed()).isTrue();
        assertThat(months.get(PERIOD).exceededAt).isEqualTo(T0);
    }

    @Test
    @DisplayName("다음 달에 도착한 정산도 예약한 달에 매긴다")
    void settleChargesReservedPeriod() {
        AiUsageMeterFacade meter = meter(properties("0.01"));
        clock.set(Instant.parse("2026-10-31T14:59:59Z"));
        AiUsageReservation reservation = meter.reserve(command(2_000, 1_000));
        clock.set(Instant.parse("2026-10-31T15:00:01Z"));

        meter.settle(settle(reservation.callId(), AiUsageStatus.TIMEOUT, null));

        assertThat(months.get("2026-10").settled).isEqualTo(ESTIMATE);
        assertThat(months.find("2026-11")).isEmpty();
    }

    @Test
    @DisplayName("월 한도를 설정하지 않았으면 예약하지 않는다")
    void missingBudgetFailsReserve() {
        assertThatThrownBy(() -> meter(properties("")).reserve(command(2_000, 1_000))).isInstanceOf(IllegalStateException.class);
        assertThat(calls.rows).isEmpty();
    }

    @Test
    @DisplayName("결과가 오지 않은 예약은 stale-after 뒤 ABANDONED 로 닫고 예상치를 확정한다 — 늦게 온 정산은 반영하지 않는다")
    void abandonsStaleReservations() {
        AiUsageProperties properties = properties("0.01");
        AiUsageMeterFacade meter = meter(properties);
        AiUsageMaintenanceFacade maintenance = maintenance(properties);
        AiUsageReservation stale = meter.reserve(command(2_000, 1_000));
        clock.set(T0.plus(Duration.ofMinutes(10)));
        AiUsageReservation fresh = meter.reserve(command(2, 100));

        assertThat(maintenance.abandonStale()).as("아직 15분이 안 됐다").isZero();
        clock.set(T0.plus(Duration.ofMinutes(16)));
        assertThat(maintenance.abandonStale()).isEqualTo(1);
        assertThat(maintenance.abandonStale()).isZero();

        assertThat(calls.rows.get(stale.callId()).status()).isEqualTo(AiUsageStatus.ABANDONED);
        assertThat(calls.rows.get(fresh.callId()).status()).isEqualTo(AiUsageStatus.PENDING);
        Month month = months.get(PERIOD);
        assertThat(month.reserved).isEqualTo(ESTIMATE + fresh.estimateCostMicros());
        assertThat(month.settled).isEqualTo(ESTIMATE);
        assertThat(month.calls).isEqualTo(1L);
        assertThat(meter.settle(settle(stale.callId(), AiUsageStatus.COMPLETED, new AiTokenUsage(1, 1, 0, 0))).applied()).isFalse();
    }

    @Test
    @DisplayName("보존 기간이 지난 닫힌 호출만 지운다 — PENDING 과 월 카운터는 남는다")
    void deletesExpiredClosedCalls() {
        AiUsageProperties properties = properties("0.01");
        AiUsageMeterFacade meter = meter(properties);
        AiUsageReservation closed = meter.reserve(command(2, 100));
        meter.settle(settle(closed.callId(), AiUsageStatus.FAILED, null));
        AiUsageReservation pending = meter.reserve(command(2, 100));

        clock.set(T0.plus(Duration.ofDays(400)));
        assertThat(maintenance(properties).deleteExpired()).as("딱 400일은 남긴다").isZero();
        clock.set(T0.plus(Duration.ofDays(400)).plusSeconds(1));
        assertThat(maintenance(properties).deleteExpired()).isEqualTo(1);

        assertThat(calls.rows).containsOnlyKeys(pending.callId());
        assertThat(months.find(PERIOD)).isPresent();
    }

    @Test
    @DisplayName("예약 UPDATE 0행 → 그 사이 다른 트랜잭션이 월 행을 만들어 실제로는 여유가 있으면 거절하지 않고 다시 시도해 예약한다 — 초과 표시도 없다")
    void racedMonthCreationRetriesInsteadOfRejecting() {
        months.missedReserves = 1;
        months.createMonthWhenMissed = true;

        AiUsageReservation reservation = meter(properties("0.01")).reserve(command(2_000, 1_000));

        assertThat(reservation.estimateCostMicros()).isEqualTo(ESTIMATE);
        assertThat(months.get(PERIOD).reserved).isEqualTo(ESTIMATE);
        assertThat(months.get(PERIOD).exceededAt).isNull();
        assertThat(calls.rows).hasSize(1);
    }

    @Test
    @DisplayName("엇갈림이 시도 상한(3회)을 넘으면 재시도 가능한 RESERVE_CONTENDED 다 — 거절도 초과 표시도 아니다")
    void repeatedContentionFailsRetryably() {
        months.createIfAbsent(PERIOD, 10_000L, T0);
        months.missedReserves = AiUsageMeterFacade.MAX_RESERVE_ATTEMPTS;

        assertThatThrownBy(() -> meter(properties("0.01")).reserve(command(2_000, 1_000)))
            .isInstanceOfSatisfying(AiUsageException.class, exception -> {
                assertThat(exception).isNotInstanceOf(AiBudgetExceededException.class);
                assertThat(exception.getErrorCode()).isEqualTo(AiUsageErrorCode.RESERVE_CONTENDED);
            });
        assertThat(months.get(PERIOD).exceededAt).isNull();
        assertThat(calls.rows).isEmpty();
    }

    @Test
    @DisplayName("예상치 하나가 월 한도보다 크면 누계가 0 이어도 거절하되 이 달의 초과 표시는 세우지 않는다(singleCallOverLimit)")
    void singleCallOverLimitDoesNotFlagMonth() {
        // 입력 1,000 + 출력 2,000 × 5 = 11,000 > 한도 10,000
        assertThatThrownBy(() -> meter(properties("0.01")).reserve(command(2_000, 2_000)))
            .isInstanceOfSatisfying(AiBudgetExceededException.class, exception -> {
                assertThat(exception.isSingleCallOverLimit()).isTrue();
                assertThat(exception.getAlert().exceedCrossed()).isFalse();
                assertThat(exception.getAlert().reservedMicros()).isZero();
                assertThat(exception.getEstimateCostMicros()).isEqualTo(11_000L);
            });
        assertThat(months.find(PERIOD)).as("월 행도 만들지 않는다").isEmpty();

        AiUsageMeterFacade meter = meter(properties("0.01"));
        meter.reserve(command(2_000, 1_000));
        assertThat(months.get(PERIOD).exceededAt).isNull();
    }

    @Test
    @DisplayName("인터럽트 플래그가 켜진 스레드에서도 정산이 끝난다 — 트랜잭션 동안은 플래그를 내리고 돌아갈 때 되살린다")
    void settleClearsInterruptDuringTransactionAndRestoresIt() {
        AiUsageMeterFacade meter = meter(properties("0.01"));
        AiUsageReservation reservation = meter.reserve(command(2_000, 1_000));

        Thread.currentThread().interrupt();
        AiUsageSettleResult result;
        try {
            result = meter.settle(settle(reservation.callId(), AiUsageStatus.NOT_SENT, null));
        } finally {
            assertThat(Thread.interrupted()).as("플래그를 되살렸다(여기서 내린다)").isTrue();
        }

        assertThat(result.applied()).isTrue();
        assertThat(calls.interruptedWhileClosing).isFalse();
        assertThat(months.get(PERIOD).reserved).isZero();
    }

    private AiUsageMeterFacade meter(AiUsageProperties properties) {
        return new AiUsageMeterFacade(new AiBudgetProcessor(calls, months, new SnowflakeIdGenerator(1, 1), properties), clock, new NoOpTransactionManager());
    }

    /** 단위 테스트용 — 트랜잭션 경계 없이 콜백만 부른다. */
    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private AiUsageMaintenanceFacade maintenance(AiUsageProperties properties) {
        return new AiUsageMaintenanceFacade(new AiUsageMaintenanceProcessor(calls, months, properties), properties, clock);
    }

    private static AiUsageProperties properties(String monthlyUsd) {
        return new AiUsageProperties(
            new AiUsageProperties.Budget(monthlyUsd, ZoneId.of("Asia/Seoul"), "0.8", List.of(7L)),
            new AiUsageProperties.Estimate(2, 1600),
            new AiUsageProperties.Pricing("test-1", Map.of("test-small", new AiUsageProperties.Pricing.Price("1", "5", "1.25", "0.1"))),
            new AiUsageProperties.ReservationSweep(Duration.ofMinutes(15), 100),
            new AiUsageProperties.Retention(400, 500, 20)
        );
    }

    private static AiUsageReserveCommand command(long inputChars, int maxTokens) {
        return new AiUsageReserveCommand("size-chart", 1, "job:1", "test-small", maxTokens, inputChars, 0, true);
    }

    private static AiUsageSettleCommand settle(long callId, AiUsageStatus outcome, AiTokenUsage usage) {
        return new AiUsageSettleCommand(callId, outcome, usage, null, null, null, null, 10);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeCallPort implements AiUsageCallRepositoryPort {

        private final Map<Long, AiUsageCall> rows = new LinkedHashMap<>();

        @Override
        public void insert(AiUsageCall call) {
            if (rows.putIfAbsent(call.id(), call) != null) {
                throw new IllegalStateException("PK");
            }
        }

        @Override
        public Optional<AiUsageCall> findById(long callId) {
            return Optional.ofNullable(rows.get(callId));
        }

        /** 닫기 UPDATE 순간 스레드 인터럽트 플래그 — 커넥션을 잡는 자리에서 플래그가 내려가 있어야 한다. */
        private Boolean interruptedWhileClosing;

        @Override
        public boolean closeIfPending(AiUsageCall closed) {
            interruptedWhileClosing = Thread.currentThread().isInterrupted();
            AiUsageCall current = rows.get(closed.id());
            if (current == null || !current.status().isPending()) {
                return false;
            }
            rows.put(closed.id(), closed);
            return true;
        }

        @Override
        public List<AiUsageCall> findPendingCalledBefore(Instant calledBefore, int limit) {
            return rows.values().stream()
                .filter(call -> call.status().isPending() && call.calledAt().isBefore(calledBefore))
                .sorted(Comparator.comparing(AiUsageCall::calledAt).thenComparing(AiUsageCall::id))
                .limit(limit)
                .toList();
        }

        @Override
        public int deleteClosedCalledBefore(Instant cutoff, int limit) {
            List<Long> ids = rows.values().stream()
                .filter(call -> !call.status().isPending() && call.calledAt().isBefore(cutoff))
                .limit(limit)
                .map(AiUsageCall::id)
                .toList();
            ids.forEach(rows::remove);
            return ids.size();
        }
    }

    private static final class Month {

        private long reserved;
        private long settled;
        private long calls;
        private long limit;
        private Instant warnedAt;
        private Instant exceededAt;
    }

    private static final class FakeMonthPort implements AiUsageMonthRepositoryPort {

        private final Map<String, Month> rows = new LinkedHashMap<>();

        Month get(String period) {
            return rows.get(period);
        }

        /** 0 보다 크면 그 횟수만큼 예약 UPDATE 가 (여유가 있어도) 0행이다 — 다른 트랜잭션과 엇갈린 창을 흉내 낸다. */
        private int missedReserves;
        /** 참이면 엇갈린 예약 직후 다른 트랜잭션이 월 행을 만든 것처럼 행을 만든다. */
        private boolean createMonthWhenMissed;

        @Override
        public boolean reserve(String period, long estimateMicros, long limitMicros, Instant now) {
            if (missedReserves > 0) {
                missedReserves--;
                if (createMonthWhenMissed) {
                    createIfAbsent(period, limitMicros, now);
                }
                return false;
            }
            Month month = rows.get(period);
            if (month == null || month.reserved + estimateMicros > limitMicros) {
                return false;
            }
            month.reserved += estimateMicros;
            month.limit = limitMicros;
            return true;
        }

        @Override
        public boolean createIfAbsent(String period, long limitMicros, Instant now) {
            if (rows.containsKey(period)) {
                return false;
            }
            Month month = new Month();
            month.limit = limitMicros;
            rows.put(period, month);
            return true;
        }

        @Override
        public void settle(String period, long estimateMicros, long actualMicros, int calls, Instant now) {
            Month month = rows.get(period);
            month.reserved = month.reserved - estimateMicros + actualMicros;
            month.settled += actualMicros;
            month.calls += calls;
        }

        @Override
        public boolean markWarned(String period, long thresholdMicros, Instant now) {
            Month month = rows.get(period);
            if (month.warnedAt != null || month.reserved < thresholdMicros) {
                return false;
            }
            month.warnedAt = now;
            return true;
        }

        @Override
        public boolean markExceededAtLimit(String period, long limitMicros, Instant now) {
            Month month = rows.get(period);
            if (month.exceededAt != null || month.reserved < limitMicros) {
                return false;
            }
            month.exceededAt = now;
            return true;
        }

        @Override
        public boolean markExceededByReserve(String period, long estimateMicros, long limitMicros, Instant now) {
            Month month = rows.get(period);
            if (month == null || month.exceededAt != null || month.reserved + estimateMicros <= limitMicros) {
                return false;
            }
            month.exceededAt = now;
            return true;
        }

        @Override
        public Optional<AiUsageMonthQueryResult> find(String period) {
            return Optional.ofNullable(rows.get(period)).map(month -> new AiUsageMonthQueryResult(
                period, month.reserved, month.settled, month.calls, month.limit, month.warnedAt, month.exceededAt
            ));
        }
    }
}
