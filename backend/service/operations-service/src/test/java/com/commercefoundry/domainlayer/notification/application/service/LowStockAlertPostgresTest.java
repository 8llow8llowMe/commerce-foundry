package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.LowStockAlertScheduler;
import com.commercefoundry.domainlayer.notification.adapter.out.lock.LowStockAlertLockAdapter;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 품절 임박 정기 점검(#229) PostgreSQL 흐름 — 생성 DDL 스냅숏을 validate 하고, 실제 JDBC ShedLock({@code operations-low-stock-alert})과
 * PostgreSQL 커밋 · 호출자 트랜잭션 중단을 확인한다. 판정 · 저장 계약 자체는 LowStockAlertPersistencePostgresTest 가 본다.
 */
@SpringJUnitConfig({LowStockAlertFlowConfig.class, LowStockAlertPostgresTest.JdbcLock.class})
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.data.jpa.repositories.enabled=false", "spring.datasource.hikari.maximum-pool-size=4",
    "spring.datasource.hikari.data-source-properties.logServerErrorDetail=false",
    "operations.notifications.low-stock-alert.enabled=true"
})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LowStockAlertPostgresTest {
    private static final String LOCK_NAME = "operations-low-stock-alert";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired LowStockAlertScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired LowStockAlertFlowConfig.SampleClock clock;
    @Autowired @Qualifier("lowStockAlertTicker") LowStockAlertFlowConfig.SampleTicker ticker;
    @Autowired LowStockAlertFlowConfig.ScriptedSource source;
    @Autowired LowStockAlertFlowConfig.ScriptedRecipients recipients;
    @Autowired LowStockAlertFlowConfig.RecordingStreams streams;
    @Autowired LowStockAlertFlowConfig.CountingPush delivery;

    @BeforeEach
    void clear() {
        jdbc.update("delete from operations.low_stock_alert_sku");
        jdbc.update("delete from operations.low_stock_alert_state");
        jdbc.update("delete from operations.notification");
        // 행은 남긴다 — JdbcLockProvider 는 이미 INSERT 한 락 이름을 기억한다.
        expireLease();
        clock.now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ticker.now = 0;
        source.reset();
        streams.reset();
        recipients.calls.set(0);
        recipients.ids = () -> List.of(7L, 8L);
        delivery.batches.set(0);
    }

    @Test
    void realShedLockAndPostgresCommitSurviveSuspendedCallerRollbackAndDeliverAfterCommit() throws Exception {
        source.serve(clock.now, List.of(11L, 12L));
        streams.expect(2);
        new TransactionTemplate(transactions).executeWithoutResult(outer -> {
            scheduler.observe();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            outer.setRollbackOnly();
        });
        assertThat(streams.await()).as("committed SSE delivery").isTrue();
        assertThat(source.calledInTransaction).containsOnly(false);
        assertThat(streams.pushedInTransaction).containsOnly(false);
        assertThat(streams.pushed).extracting(Notification::recipientMemberId).containsExactlyInAnyOrder(7L, 8L);
        assertThat(jdbc.queryForList("select recipient_member_id from operations.notification order by recipient_member_id", Long.class))
            .containsExactly(7L, 8L);
        assertThat(trackedIds()).containsExactly(11L, 12L);
        assertThat(jdbc.queryForObject("select count(*) from operations.shedlock where name = ?", Long.class, LOCK_NAME)).isEqualTo(1L);

        // 다음 주기 — 그대로 임박이면 새 알림 0건.
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        source.serve(clock.now, List.of(11L, 12L));
        run();
        assertThat(jdbc.queryForObject("select count(*) from operations.notification", Long.class)).isEqualTo(2L);
        assertThat(delivery.batches).hasValue(1);
        // 거부가 아니라 수락돼 워터마크가 이 주기 관측 시각으로 전진했다.
        assertThat(jdbc.queryForObject("select last_observed_at from operations.low_stock_alert_state", Timestamp.class).toInstant()).isEqualTo(clock.now);
    }

    @Test
    void heldLeaseSkipsWholeScanThenExpiredLeaseRunsAgain() throws Exception {
        source.serve(clock.now, List.of(11L));
        holdLease(Instant.now().plus(Duration.ofMinutes(10)));
        scheduler.observe();
        assertThat(recipients.calls).hasValue(0);
        assertThat(source.cursors).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from operations.low_stock_alert_state", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operations.notification", Long.class)).isZero();

        streams.expect(2);
        run();
        assertThat(streams.await()).isTrue();
        assertThat(recipients.calls).hasValue(1);
        assertThat(trackedIds()).containsExactly(11L);
        assertThat(jdbc.queryForObject("select count(*) from operations.notification", Long.class)).isEqualTo(2L);
    }

    /** 시험 시계를 앞으로 민 점검은 lease 를 그 시각까지 남기므로, 다음 점검 전에 실제 lease 를 지난 시각으로 돌린다. */
    private void run() {
        expireLease();
        scheduler.observe();
    }

    private int expireLease() {
        return jdbc.update("update operations.shedlock set lock_until = ? where name = ?", Timestamp.from(Instant.now().minusSeconds(1)), LOCK_NAME);
    }

    private void holdLease(Instant until) {
        if (jdbc.update("update operations.shedlock set lock_until = ? where name = ?", Timestamp.from(until), LOCK_NAME) == 0) {
            jdbc.update("insert into operations.shedlock (name, lock_until, locked_at, locked_by) values (?, ?, ?, ?)", LOCK_NAME, Timestamp.from(until),
                Timestamp.from(Instant.now()), "other-instance");
        }
    }

    private List<Long> trackedIds() {
        return jdbc.queryForList("select sku_id from operations.low_stock_alert_sku order by sku_id", Long.class);
    }

    /** 운영과 같은 ShedLock 테이블({@code commerce.messaging.outbox.relay.lock-table})과 실제 락 Adapter. */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(LowStockAlertLockAdapter.class)
    static class JdbcLock {
        @Bean
        LockProvider lowStockLockProvider(DataSource dataSource) {
            return new JdbcLockProvider(dataSource, "operations.shedlock");
        }
    }
}
