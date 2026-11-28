package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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

/** 생성 DDL 스냅숏을 PG17 에 적용하고 validate 한 뒤, 공통 계약과 실제 NOWAIT · CHECK · 첫 INSERT 경합을 확인한다. */
@SpringJUnitConfig(LowStockAlertPersistenceConfig.class)
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.data.jpa.repositories.enabled=false", "spring.datasource.hikari.maximum-pool-size=4",
    "spring.datasource.hikari.data-source-properties.logServerErrorDetail=false"
})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LowStockAlertPersistencePostgresTest extends LowStockAlertPersistenceContract {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void lockedWatermarkMakesSecondScanFailImmediatelyWithNowait() throws Exception {
        accept(scan(NOW.minusSeconds(1), 3L));
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
            var state = stateRows();
            clock.now = NOW.plusSeconds(600);
            // 처리 경과는 시험 ticker 로 정상 범위에 두고, 실제 벽시계로는 NOWAIT 가 기다리지 않았는지만 본다.
            long wallStarted = System.nanoTime();
            assertThatThrownBy(() -> processor.accept(scan(NOW.plusSeconds(599), 3L, 4L), RECIPIENTS, SETTINGS, ticker.now))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("55P03"));
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - wallStarted)).isLessThan(5);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(stateRows()).isEqualTo(state);
            assertThat(trackedIds()).containsExactly(3L);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void twoFirstScansRaceAndOnlyOneCommitsWithoutUpsert() throws Exception {
        var empty = new CountDownLatch(2);
        var insert = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<Future<Throwable>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(executor.submit(() -> {
                    try {
                        transaction().executeWithoutResult(status -> {
                            assertThat(states.findLocked()).isEmpty();
                            empty.countDown();
                            await(insert);
                            states.insertInitial(new LowStockAlertPolicy.State(NOW, NOW, null));
                        });
                        return null;
                    } catch (Throwable error) {
                        return error;
                    }
                }));
            }
            await(empty);
            insert.countDown();
            var failures = List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)).stream()
                .filter(Objects::nonNull).toList();
            assertThat(failures).hasSize(1);
            assertThat(sqlState(failures.getFirst())).isEqualTo("23505");
            assertThat(stateRows()).hasSize(1);
        } finally {
            insert.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void databaseConstraintsRejectWrongSourceAlertAfterAcceptAndNonPositiveSku() {
        accept(scan(NOW.minusSeconds(1), 3L));
        for (String update : List.of("update operations.low_stock_alert_state set source = 'OTHER'",
            "update operations.low_stock_alert_state set last_alert_at = last_accepted_local_at + interval '1 microsecond'",
            "update operations.low_stock_alert_sku set sku_id = 0")) {
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> jdbc.update(update)))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23514"));
        }
    }

    private static String sqlState(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch watchdog");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", error);
        }
    }
}
