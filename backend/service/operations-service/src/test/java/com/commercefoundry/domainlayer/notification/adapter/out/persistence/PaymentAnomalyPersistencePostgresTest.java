package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 생성 DDL을 PG17에 적용하고 validate한다. 합성 fanout/경합이며 HTTP·SSE·포화 JVM 안전성 증거가 아니다. */
@SpringJUnitConfig(PaymentAnomalyPersistenceConfig.class)
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.properties.hibernate.use_sql_comments=false",
    "spring.data.jpa.repositories.enabled=false", "spring.datasource.hikari.maximum-pool-size=4",
    "spring.datasource.hikari.data-source-properties.logServerErrorDetail=false"
})
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAnomalyPersistencePostgresTest extends PaymentAnomalyPersistenceContract {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void postgresEnvironment() throws Exception {
        var env = new java.util.LinkedHashMap<String, Object>(environment);
        env.put("image", POSTGRES.getDockerImageName());
        var info = POSTGRES.getContainerInfo();
        env.put("imageId", info.getImageId());
        var limits = info.getHostConfig();
        env.put("containerMemoryLimitBytes", java.util.Objects.toString(limits.getMemory(), "unset"));
        env.put("containerNanoCpus", java.util.Objects.toString(limits.getNanoCPUs(), "unset"));
        env.put("containerCpuQuota", java.util.Objects.toString(limits.getCpuQuota(), "unset"));
        env.put("containerCpuPeriod", java.util.Objects.toString(limits.getCpuPeriod(), "unset"));
        var digests = org.testcontainers.DockerClientFactory.instance().client().inspectImageCmd(POSTGRES.getDockerImageName()).exec().getRepoDigests();
        env.put("imageDigests", digests == null ? List.of() : digests);
        environment = Map.copyOf(env);
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
    }

    @Test
    @Order(1)
    void firstFiveSignalsTimesThousandAndExistingRepeatCommitFullBatch() {
        var recipients = LongStream.rangeClosed(1, 1000).boxed().toList();
        for (int iteration = 0; iteration < 2; iteration++) {
            clock.now = NOW.plusSeconds(iteration * 3600L);
            ticker.now = 0;
            var observation = sample(clock.now, true);
            var result = measure("E2_FULL_5000", 5000, iteration + 1, () -> accept(observation, recipients, SETTINGS));
            // 정상 최대치 실패는 여기서 즉시 중단한다. 실패를 허용하는 진단 분기는 없다.
            assertThat(result.failure()).isNull();
            assertSql(result.total(), 1, iteration == 0 ? 5 : 0, iteration == 0 ? 0 : 5, 5000);
            assertThat(AnomalyProbeMeter.value(result.total(), "orderedNowaitSelect")).isEqualTo(1);
            assertThat(result.saved()).hasSize(5000);
            assertThat(result.saved().stream().map(Notification::id).distinct()).hasSize(5000);
            assertThat(result.saved()).allSatisfy(n -> {
                assertThat(n.sourceType()).isEqualTo("PAYMENT_ANOMALY_ALERT");
                assertThat(n.sourceId()).isEqualTo(n.id());
                assertThat(n.version()).isZero();
                assertThat(n.unread()).isTrue();
            });
            var stored = transaction().execute(status -> repository.findAllById(result.saved().stream().map(Notification::id).toList()));
            assertThat(stored).hasSize(5000);
            var byId = stored.stream().collect(java.util.stream.Collectors.toMap(e -> e.getId(), e -> e));
            for (var expected : result.saved()) {
                var actual = byId.get(expected.id());
                assertThat(actual.getRecipientMemberId()).isEqualTo(expected.recipientMemberId());
                assertThat(actual.getSourceType()).isEqualTo(expected.sourceType());
                assertThat(actual.getSourceId()).isEqualTo(expected.id());
                assertThat(actual.getSeverity()).isEqualTo(expected.severity());
                assertThat(actual.getTitle()).isEqualTo(expected.title());
                assertThat(actual.getCreatedAt()).isNotNull();
                assertThat(actual.getUpdatedAt()).isNotNull();
                assertThat(actual.getVersion()).isZero();
            }
            for (long recipient : recipients) {
                assertThat(result.saved().stream().filter(n -> n.recipientMemberId() == recipient)).hasSize(5);
            }
            assertThat(jdbc.queryForObject("select count(*) from operations.notification", Long.class)).isEqualTo((iteration + 1L) * 5000);
            assertThat(jdbc.queryForObject("select count(*) from operations.payment_anomaly_alert_state where version=?", Long.class, iteration)).isEqualTo(5);
        }
    }

    @Test
    @Order(2)
    void fiveThousandFlushedNotificationsAndAllStatesRollBackOnPostFlushFailure() {
        for (boolean existing : List.of(false, true)) {
            clock.now = NOW;
            ticker.now = 0;
            jdbc.update("delete from operations.payment_anomaly_alert_state");
            jdbc.update("delete from operations.notification");
            if (existing) {
                accept(sample(NOW, true), List.of(1L), SETTINGS);
            }
            var before = stateRows();
            assertThat(before).hasSize(existing ? 5 : 0);
            var noticesBefore = jdbc.queryForList("select * from operations.notification order by id");
            clock.now = NOW.plusSeconds(3600);
            var observation = sample(clock.now, true);
            var recipients = LongStream.rangeClosed(1, 1000).boxed().toList();
            var result = measure("E2_5000_POST_FLUSH_ROLLBACK", 5000, 1, () -> {
                assertThat(accept(observation, recipients, SETTINGS)).hasSize(5000);
                throw new IllegalStateException("synthetic post-5000-flush failure");
            });
            assertThat(result.failure()).isInstanceOf(IllegalStateException.class);
            assertSql(result.explicit(), 1, existing ? 0 : 5, existing ? 5 : 0, 5000);
            assertThat(stateRows()).isEqualTo(before);
            assertThat(jdbc.queryForList("select * from operations.notification order by id")).isEqualTo(noticesBefore);
        }
    }

    @Test
    void actualOrderedNowaitLocksEveryFamilyAcrossTransactions() throws Exception {
        accept(sample(NOW, false), List.of(1L), SETTINGS);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> transaction().executeWithoutResult(status -> {
                assertThat(states.findLocked()).isPresent();
                locked.countDown();
                await(release);
            }));
            await(locked);
            var blocked = measure("E2_NOWAIT", 0, 1, () -> { states.findLocked(); return List.of(); });
            assertThat(sqlState(blocked.failure())).isEqualTo("55P03");
            assertThat(AnomalyProbeMeter.value(blocked.total(), "orderedNowaitSelect")).isEqualTo(1);
            for (String family : FAMILIES) {
                try (var connection = jdbc.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("select family from operations.payment_anomaly_alert_state where family=? for update nowait")) {
                        statement.setString(1, family);
                        assertThatThrownBy(statement::executeQuery).isInstanceOf(java.sql.SQLException.class)
                            .satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("55P03"));
                    } finally {
                        connection.rollback();
                    }
                }
            }
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void twoEmptyReadsRaceOnlyOneInitialInsertCommitsWithoutUpsert() throws Exception {
        var empty = new CountDownLatch(2);
        var insert = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(executor.submit(() -> measure("E2_TWO_EMPTY_RACE", 0, 1, () -> {
                    assertThat(states.findLocked()).isEmpty();
                    empty.countDown();
                    await(insert);
                    states.insertInitial(initial());
                    return List.of();
                })));
            }
            await(empty);
            insert.countDown();
            var results = List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.failure() == null)).hasSize(1);
            for (var result : results) {
                assertThat(AnomalyProbeMeter.value(result.total(), "stateSelectRows")).isEqualTo(1);
                assertThat(AnomalyProbeMeter.value(result.total(), "stateUpdateRows")).isZero();
                if (result.failure() != null) {
                    assertThat(sqlState(result.failure())).isEqualTo("23505");
                }
            }
            assertThat(stateRows()).hasSize(5);
        } finally {
            insert.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void emptyReadDoesNotSwitchToUpdateAfterOtherTransactionCommits() throws Exception {
        var empty = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var loser = executor.submit(() -> measure("E2_EMPTY_THEN_OTHER_COMMIT", 0, 1, () -> {
                assertThat(states.findLocked()).isEmpty();
                empty.countDown();
                await(committed);
                states.insertInitial(initial());
                return List.of();
            }));
            await(empty);
            accept(sample(NOW, false), List.of(1L), SETTINGS);
            var winner = stateRows();
            committed.countDown();
            var result = loser.get(20, TimeUnit.SECONDS);
            assertThat(sqlState(result.failure())).isEqualTo("23505");
            assertThat(AnomalyProbeMeter.value(result.total(), "stateSelectRows")).isEqualTo(1);
            assertThat(AnomalyProbeMeter.value(result.total(), "stateUpdateRows")).isZero();
            assertThat(stateRows()).isEqualTo(winner);
        } finally {
            committed.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void uncommittedInitialPrimaryKeyWaitHitsJdbcDeadlineAndRollsBackEarlierWrites() throws Exception {
        var blockerReady = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var workerWritten = new CountDownLatch(1);
        var pid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> transaction().executeWithoutResult(status -> {
                states.insertInitial(initial());
                blockerReady.countDown();
                awaitLong(releaseBlocker);
                status.setRollbackOnly();
            }));
            await(blockerReady);
            var waiter = executor.submit(() -> measure("E2_INITIAL_PK_WAIT_TIMEOUT", 2, 1, () -> {
                pid.set(meter.excluded(() -> jdbc.queryForObject("select pg_backend_pid()", Integer.class)));
                batch.insertAll(AnomalyProbeStore.fixture(2));
                assertThat(states.findLocked()).isEmpty();
                workerWritten.countDown();
                states.insertInitial(initial());
                return List.of();
            }));
            await(workerWritten);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where pid=? and wait_event_type='Lock'", Integer.class, pid.get())).isEqualTo(1));
            var result = waiter.get(25, TimeUnit.SECONDS);
            assertThat(sqlState(result.failure())).isEqualTo("57014");
            assertThat(AnomalyProbeMeter.value(result.explicit(), "insertRows")).isEqualTo(2);
            assertThat(AnomalyProbeMeter.value(result.total(), "stateUpdateRows")).isZero();
            releaseBlocker.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(stateRows()).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from operations.notification", Long.class)).isZero();
            transaction().executeWithoutResult(status -> assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1));
            emit("E2_INITIAL_PK_WAIT_PROOF", 2, 1, "OBSERVED", Map.of("pgLockWait", true, "txTimeoutSeconds", 15,
                "sqlState", sqlState(result.failure()), "statisticsLimit", "concurrent Hibernate statistics are factory-global; use per-thread JDBC counters"));
        } finally {
            releaseBlocker.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void databaseConstraintsRejectInvalidKeyStatusSeverityNullsAndChronology() {
        accept(sample(NOW, false), List.of(1L), SETTINGS);
        for (String update : List.of("family='OTHER'", "status='OTHER'", "status='ACTIVE'", "severity='INFO'",
            "last_observed_at=last_accepted_local_at + interval '1 second'",
            "status='ACTIVE',severity='CRITICAL',first_detected_at=last_observed_at,last_alert_at=last_accepted_local_at")) {
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> jdbc.update(
                "update operations.payment_anomaly_alert_state set " + update + " where family='IN_PROGRESS'")))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23514"));
        }
        var before = stateRows();
        var result = measure("E2_WRONG_KEY_FAIL_CLOSED", 0, 1, () -> {
            meter.excluded(() -> {
                jdbc.execute("alter table operations.payment_anomaly_alert_state drop constraint ck_payment_anomaly_alert_state_family");
                jdbc.update("update operations.payment_anomaly_alert_state set family='OTHER' where family='UNKNOWN'");
                return null;
            });
            accept(sample(NOW.plusSeconds(1), true), List.of(1L), SETTINGS);
            return List.of();
        });
        assertThat(result.failure()).isNotNull();
        assertSql(result.total(), 1, 0, 0, 0);
        assertThat(stateRows()).isEqualTo(before);
    }

    @Test
    void localNanosecondsRoundTripThroughPgMicrosecondsAndReplayStillDoesNotWrite() {
        clock.now = NOW.plusNanos(999);
        accept(sample(NOW, true), List.of(1L), SETTINGS);
        var restored = transaction().execute(status -> states.findLocked().orElseThrow());
        assertThat(restored.lastAcceptedLocalAt()).isEqualTo(NOW.plusNanos(1000));
        assertThat(restored.incidents().values()).allSatisfy(i -> assertThat(i.lastAlertAt()).isEqualTo(restored.lastAcceptedLocalAt()));
        var before = stateRows();
        var replay = measure("E2_NANO_REPLAY", 0, 1, () -> accept(sample(NOW, true), List.of(1L), SETTINGS));
        assertThat(replay.failure()).isNull();
        assertSql(replay.total(), 1, 0, 0, 0);
        assertThat(stateRows()).isEqualTo(before);
    }

    private static void await(CountDownLatch latch) { await(latch, 10); }
    private static void awaitLong(CountDownLatch latch) { await(latch, 35); }
    private static void await(CountDownLatch latch, int seconds) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) { throw new IllegalStateException("synthetic latch watchdog"); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", error);
        }
    }
}
