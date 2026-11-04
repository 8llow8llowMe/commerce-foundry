package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PG17/기존 DDL validate, 정상 저장과 기대 rollback 실험을 구별한다. 시간/heap은 CI 성능 합격 기준이 아니다. */
@SpringJUnitConfig(AnomalyProbeConfig.class)
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.properties.hibernate.use_sql_comments=false",
    "spring.data.jpa.repositories.enabled=false", "spring.datasource.hikari.maximum-pool-size=4",
    "spring.datasource.hikari.data-source-properties.logServerErrorDetail=false"
})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnomalyStorageProbePostgresTest extends AnomalyProbeSupport {
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
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        var env = new LinkedHashMap<>(environment);
        env.put("image", POSTGRES.getDockerImageName());
        var info = POSTGRES.getContainerInfo();
        var limits = info.getHostConfig();
        env.put("imageId", java.util.Objects.toString(info.getImageId(), "unavailable"));
        env.put("containerMemoryLimitBytes", java.util.Objects.toString(limits.getMemory(), "unset"));
        env.put("containerNanoCpus", java.util.Objects.toString(limits.getNanoCPUs(), "unset"));
        env.put("containerCpuQuota", java.util.Objects.toString(limits.getCpuQuota(), "unset"));
        env.put("containerCpuPeriod", java.util.Objects.toString(limits.getCpuPeriod(), "unset"));
        var digests = DockerClientFactory.instance().client().inspectImageCmd(POSTGRES.getDockerImageName()).exec().getRepoDigests();
        env.put("imageDigests", digests == null ? List.of() : digests);
        environment = Map.copyOf(env);
    }

    @Test
    void minimumComparisonAndSelectedCandidateMaximum() {
        // 공유 워밍업 1회. A/B는 동일 형상의 서로 겹치지 않는 합성 입력이다.
        committed(AnomalyProbeStore.Mode.A_SAVE_ALL, 1000, 0);
        clear();
        for (var mode : List.of(AnomalyProbeStore.Mode.A_SAVE_ALL, AnomalyProbeStore.Mode.B_PERSIST)) {
            for (int n : List.of(1, 1000)) {
                committed(mode, n, 1);
                clear();
            }
        }
        emit("SELECTION", 1000, 1, "A_PREFERRED_AFTER_EQUAL_CORRECTNESS_GATES",
            Map.of("reason", "same new detection/SQL/mapping/commit gates; no performance-only ranking", "timingLimit", "diagnostic 5000 <=10s; not CI assert"));
        // 첫 실패는 assertion으로 즉시 중단한다. 3표본으로 p95/p99를 계산하지 않는다.
        for (int iteration = 1; iteration <= 3; iteration++) {
            committed(AnomalyProbeStore.Mode.A_SAVE_ALL, 5000, iteration);
            clear();
        }
        for (int n : List.of(1, 100, 1000)) {
            committed(AnomalyProbeStore.Mode.BASELINE, n, 1);
            clear();
        }
        var input = AnomalyProbeStore.fixture(5000);
        var baseline = measure("BASELINE_5000_ONCE_15S", 5000, 1, () -> store().insert(AnomalyProbeStore.Mode.BASELINE, input));
        if (baseline.failure() == null) {
            assertNewSql(baseline.total(), 5000);
            assertMapping(input, baseline.saved());
            assertThat(AnomalyProbeMeter.value(baseline.explicit(), "hibernateFlush")).isEqualTo(5000);
        } else {
            assertThat(isTimeout(baseline.failure())).as("only a timeout is an allowed diagnostic baseline failure").isTrue();
            assertEmpty();
        }
    }

    @Test
    void actualMidpointUniqueFailureAndPostFlushFailureRollBack5000() {
        for (var mode : List.of(AnomalyProbeStore.Mode.A_SAVE_ALL, AnomalyProbeStore.Mode.B_PERSIST)) {
            var input = new java.util.ArrayList<>(AnomalyProbeStore.fixture(5000));
            var original = input.get(2500);
            var first = input.getFirst();
            input.set(2500, new Notification(original.id(), original.recipientMemberId(), original.kind(), original.severity(), original.title(),
                original.linkPath(), first.sourceType(), first.sourceId(), null, null, null));
            var unique = measure(mode + "_EXPECTED_UNIQUE_ROLLBACK", 5000, 1, () -> {
                store().insert(mode, input.subList(0, 2500));
                return store().insert(mode, input.subList(2500, 5000));
            });
            assertThat(unique.failure()).isNotNull();
            assertThat(sqlState(unique.failure())).isEqualTo("23505");
            assertThat(AnomalyProbeMeter.value(unique.explicit(), "insertRows")).isGreaterThanOrEqualTo(2500);
            assertEmpty();
            var complete = AnomalyProbeStore.fixture(5000);
            var postFlush = measure(mode + "_EXPECTED_POST_FLUSH_ROLLBACK", 5000, 1, () -> {
                store().insert(mode, complete);
                throw new IllegalStateException("synthetic post-flush failure");
            });
            assertThat(postFlush.failure()).isInstanceOf(IllegalStateException.class);
            assertNewSql(postFlush.explicit(), 5000);
            assertEmpty();
        }
    }

    @Test
    void realDatabaseWaitHitsTransactionJdbcDeadlineAndReturnsConnection() throws Exception {
        for (var mode : List.of(AnomalyProbeStore.Mode.A_SAVE_ALL, AnomalyProbeStore.Mode.B_PERSIST)) {
            var input = AnomalyProbeStore.fixture(4);
            var workerPid = new AtomicInteger();
            var written = new CountDownLatch(1);
            var continueInsert = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try (var blocker = jdbc.getDataSource().getConnection()) {
                blocker.setAutoCommit(false);
                blockKey(blocker, input.get(2));
                var future = executor.submit(() -> measure(mode + "_EXPECTED_DB_WAIT_TIMEOUT", 4, 1, () -> {
                    workerPid.set(meter.excluded(() -> jdbc.queryForObject("select pg_backend_pid()", Integer.class)));
                    store().insert(mode, input.subList(0, 2));
                    written.countDown();
                    await(continueInsert);
                    return store().insert(mode, input.subList(2, 4));
                }));
                try {
                    assertThat(written.await(8, TimeUnit.SECONDS)).as("actual prefix INSERTs flushed").isTrue();
                    continueInsert.countDown();
                    boolean observedLock = false;
                    long watchdog = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (!future.isDone() && System.nanoTime() < watchdog) {
                        observedLock = jdbc.queryForObject("select count(*) from pg_stat_activity where pid=? and wait_event_type='Lock'",
                            Integer.class, workerPid.get()) == 1;
                        if (observedLock) {
                            break;
                        }
                        Thread.sleep(25);
                    }
                    assertThat(observedLock).as("PG server observed actual lock wait, not just Java barrier").isTrue();
                    var result = future.get(25, TimeUnit.SECONDS); // 별도 watchdog, tx/JDBC 15초의 대체가 아니다.
                    assertThat(result.failure()).isNotNull();
                    assertThat(isTimeout(result.failure())).isTrue();
                    assertThat(AnomalyProbeMeter.value(result.explicit(), "insertRows")).isGreaterThanOrEqualTo(2);
                    emit(mode + "_WAIT_PROOF", 4, 1, "OBSERVED", Map.of("pgLockWait", true, "txTimeoutSeconds", 15, "sqlState", sqlState(result.failure())));
                } finally {
                    continueInsert.countDown();
                    blocker.rollback(); // watchdog/검증 실패여도 blocker 해제 후 worker를 끝까지 회수한다.
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdown();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("worker joined").isTrue();
            }
            assertEmpty();
            // 별도 tx/새 borrow가 성공하고 반환됨을 확인한다. pool 전체의 누수 부재를 주장하지 않는다.
            transaction().executeWithoutResult(status -> assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1));
        }
    }

    private static void blockKey(Connection connection, Notification n) throws Exception {
        try (var statement = connection.prepareStatement("insert into operations.notification "
            + "(id,recipient_member_id,kind,severity,title,link_path,source_type,source_id,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,0,?,?)")) {
            statement.setLong(1, n.id());
            statement.setLong(2, n.recipientMemberId());
            statement.setString(3, n.kind().name());
            statement.setString(4, n.severity().name());
            statement.setString(5, n.title());
            statement.setString(6, n.linkPath());
            statement.setString(7, n.sourceType());
            statement.setLong(8, n.sourceId());
            statement.setTimestamp(9, Timestamp.from(Instant.now()));
            statement.setTimestamp(10, Timestamp.from(Instant.now()));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void clear() {
        jdbc.update("delete from operations.notification");
    }

    private void assertEmpty() {
        transaction().executeWithoutResult(status -> assertThat(repository.count()).isZero());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier watchdog");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", error);
        }
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().contains("Timeout") || cause.getClass().getSimpleName().contains("TimedOut")) {
                return true;
            }
        }
        return "57014".equals(sqlState(error));
    }
}
