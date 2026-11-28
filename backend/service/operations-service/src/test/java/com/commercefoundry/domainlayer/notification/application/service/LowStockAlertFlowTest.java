package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.LowStockAlertScheduler;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockAlertLockPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 품절 임박 정기 점검(#229) H2 흐름 — 실제 스케줄러 빈 → Facade → 원천 순회 → 저장 트랜잭션 → 커밋 뒤 SSE 전달.
 * 이슈 완료 기준 "기준 아래로 내려가면 알림": product 커서 조회에 새로 나타난 SKU(판매 가능 수량 ≤ 기준)가 수신자마다 알림 1건이 된다.
 */
@SpringJUnitConfig({LowStockAlertFlowConfig.class, LowStockAlertFlowTest.PassThroughLock.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:low-stock-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.default_schema=operations", "spring.data.jpa.repositories.enabled=false",
    "operations.notifications.low-stock-alert.enabled=true", "operations.notifications.low-stock-alert.max-pages=2"
})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LowStockAlertFlowTest {
    private static final Instant START = Instant.parse("2026-11-28T01:00:00Z");

    @Autowired LowStockAlertScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
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
        clock.now = START;
        ticker.now = 0;
        source.reset();
        streams.reset();
        recipients.calls.set(0);
        recipients.ids = () -> List.of(7L, 8L);
        delivery.batches.set(0);
    }

    @Test
    void fallingBelowThresholdAlertsEachRecipientOnceThenStaysQuietUntilResolvedAndReentered() throws Exception {
        // 1) 기준 아래로 내려간 SKU 두 개 → 수신자마다 요약 1건(WARNING), 커밋 뒤 SSE 로 전달된다.
        streams.expect(2);
        cycle(Duration.ZERO, List.of(11L, 12L));
        assertThat(streams.await()).as("committed SSE delivery").isTrue();
        assertThat(recipientsOf(notices())).containsExactlyInAnyOrder(7L, 8L);
        assertThat(notices()).allSatisfy(row -> {
            assertThat(row.get("kind")).isEqualTo("LOW_STOCK");
            assertThat(row.get("severity")).isEqualTo("WARNING");
            assertThat(row.get("title")).isEqualTo("품절 임박 상품 옵션 2개가 새로 생겼습니다. 재고를 확인해 주세요.");
            assertThat(row.get("link_path")).isEqualTo("/admin/stocks");
            assertThat(row.get("source_type")).isEqualTo("LOW_STOCK_ALERT");
        });
        assertThat(streams.pushed).extracting(Notification::recipientMemberId).containsExactlyInAnyOrder(7L, 8L);
        assertThat(streams.pushedInTransaction).containsOnly(false);
        assertThat(source.calledInTransaction).containsOnly(false);
        assertThat(trackedIds()).containsExactly(11L, 12L);
        assertThat(delivery.batches).hasValue(1);

        // 2) 다음 주기에도 그대로 임박 → 새 알림 0건, 전달 0회. 거부가 아니라 수락돼 워터마크가 이 주기 관측 시각으로 전진한다.
        cycle(Duration.ofMinutes(10), List.of(11L, 12L));
        assertThat(notices()).hasSize(2);
        assertThat(delivery.batches).hasValue(1);
        assertThat(watermark()).isEqualTo(clock.now);

        // 3) 완전 스캔에서 처음 빠짐 → 부재 표시만, 추적은 유지.
        cycle(Duration.ofMinutes(10), List.of());
        assertThat(trackedIds()).containsExactly(11L, 12L);
        assertThat(missingCount()).isEqualTo(2);

        // 4) resolve-after(6시간) 이상 연속 부재 → 추적 끝.
        cycle(Duration.ofHours(6), List.of());
        assertThat(trackedIds()).isEmpty();
        assertThat(notices()).hasSize(2);

        // 5) 다시 기준 아래로 → 새 알림 1건씩(재진입), 다시 전달된다.
        streams.expect(2);
        cycle(Duration.ofMinutes(10), List.of(11L));
        assertThat(streams.await()).as("re-entry SSE delivery").isTrue();
        assertThat(notices()).hasSize(4);
        assertThat(notices().subList(2, 4)).allSatisfy(row ->
            assertThat(row.get("title")).isEqualTo("품절 임박 상품 옵션 1개가 새로 생겼습니다. 재고를 확인해 주세요."));
        assertThat(trackedIds()).containsExactly(11L);
        assertThat(delivery.batches).hasValue(2);
        assertThat(streams.pushed).hasSize(4);
        assertThat(source.cursors).containsOnly(0L);
    }

    @Test
    void recipientOrSourceFailureWritesNothingAndNextCycleStartsFromFirstPage() throws Exception {
        recipients.ids = () -> {
            throw new NotificationException(NotificationErrorCode.RECIPIENTS_UNAVAILABLE);
        };
        scheduler.observe();
        assertThat(source.cursors).isEmpty();
        assertNothingStored();

        // 두 번째 페이지에서 원천 장애 — 첫 페이지 SKU 도 반영하지 않고 다시 부르지 않는다.
        recipients.ids = () -> List.of(7L, 8L);
        source.serve(clock.now, List.of(11L), List.of(12L));
        source.failAt(11L);
        scheduler.observe();
        assertThat(source.cursors).containsExactly(0L, 11L);
        assertNothingStored();
        assertThat(delivery.batches).hasValue(0);

        // 다음 주기는 커서 0 부터 처음부터 읽어 정상 반영한다.
        source.cursors.clear();
        streams.expect(2);
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        source.serve(clock.now, List.of(11L), List.of(12L));
        scheduler.observe();
        assertThat(streams.await()).isTrue();
        assertThat(source.cursors).containsExactly(0L, 11L);
        assertThat(trackedIds()).containsExactly(11L, 12L);
        assertThat(notices()).hasSize(2);
    }

    @Test
    void noRecipientSkipsScanAndStorage() {
        recipients.ids = List::of;
        source.serve(clock.now, List.of(11L));
        scheduler.observe();
        assertThat(recipients.calls).hasValue(1);
        assertThat(source.cursors).isEmpty();
        assertNothingStored();
        assertThat(delivery.batches).hasValue(0);
    }

    @Test
    void truncatedScanAlertsSeenSkusButDoesNotJudgeAbsence() throws Exception {
        streams.expect(2);
        cycle(Duration.ZERO, List.of(1L, 2L));
        assertThat(streams.await()).isTrue();

        // max-pages=2: 세 번째 페이지(커서 22)는 읽지 않는다. 본 SKU 만 신규로 반영하고, 빠진 1 · 2 를 부재로 표시하지 않는다.
        source.cursors.clear();
        streams.expect(2);
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        source.serve(clock.now, List.of(21L), List.of(22L), List.of(23L));
        scheduler.observe();
        assertThat(streams.await()).isTrue();
        assertThat(source.cursors).containsExactly(0L, 21L);
        assertThat(trackedIds()).containsExactly(1L, 2L, 21L, 22L);
        assertThat(missingCount()).isZero();
        assertThat(notices()).hasSize(4);
        assertThat(notices().subList(2, 4)).allSatisfy(row ->
            assertThat(row.get("title")).isEqualTo("품절 임박 상품 옵션 2개가 새로 생겼습니다. 재고를 확인해 주세요."));
    }

    @Test
    void scanOverLockAtMostIsDiscardedWithoutStorageOrDelivery() {
        // 락을 얻은 뒤(Facade 가 시작 시각을 잰 뒤) 원격 조회가 lock-at-most(기본 5분)를 넘긴 점검 — 저장 트랜잭션이 거부한다.
        recipients.ids = () -> {
            ticker.now = Duration.ofMinutes(5).plusNanos(1).toNanos();
            return List.of(7L, 8L);
        };
        source.serve(clock.now, List.of(11L, 12L));
        scheduler.observe();
        assertThat(source.cursors).containsExactly(0L);
        assertNothingStored();
        assertThat(delivery.batches).hasValue(0);
        assertThat(streams.pushed).isEmpty();
    }

    /** 다음 주기: 로컬 시계와 첫 페이지 관측 시각을 함께 앞으로 민다. 한 페이지 완전 스캔. */
    private void cycle(Duration after, List<Long> skuIds) {
        clock.now = clock.now.plus(after);
        source.serve(clock.now, skuIds);
        scheduler.observe();
    }

    private void assertNothingStored() {
        assertThat(jdbc.queryForObject("select count(*) from operations.low_stock_alert_state", Long.class)).isZero();
        assertThat(trackedIds()).isEmpty();
        assertThat(notices()).isEmpty();
    }

    private List<Map<String, Object>> notices() {
        return jdbc.queryForList("select * from operations.notification order by id");
    }

    private static List<Long> recipientsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> ((Number) row.get("recipient_member_id")).longValue()).toList();
    }

    private List<Long> trackedIds() {
        return jdbc.queryForList("select sku_id from operations.low_stock_alert_sku order by sku_id", Long.class);
    }

    private Instant watermark() {
        return jdbc.queryForObject("select last_observed_at from operations.low_stock_alert_state", Timestamp.class).toInstant();
    }

    private long missingCount() {
        return jdbc.queryForObject("select count(*) from operations.low_stock_alert_sku where missing_since is not null", Long.class);
    }

    /** H2 흐름은 단일 스레드라 락을 통과시킨다. 실제 ShedLock 은 LowStockAlertPostgresTest 가 본다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PassThroughLock {
        @Bean
        LowStockAlertLockPort lowStockAlertLockPort() {
            return Runnable::run;
        }
    }
}
