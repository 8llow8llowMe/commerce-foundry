package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.application.port.out.LowStockAlertStatePort;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 와 PostgreSQL 이 같은 실제 저장 경계(Processor → Adapter → Entity)의 기능 · 원자성 계약을 실행한다(#229). */
abstract class LowStockAlertPersistenceContract {
    static final Instant NOW = Instant.parse("2026-11-28T01:00:00Z");
    static final LowStockAlertPolicy.Settings SETTINGS = LowStockAlertPolicy.Settings.defaults();
    static final List<Long> RECIPIENTS = List.of(7L, 8L);

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LowStockAlertStatePort states;
    @Autowired LowStockAlertStateProcessor processor;
    @Autowired LowStockAlertPersistenceConfig.SampleClock clock;
    @Autowired @Qualifier("lowStockAlertTicker") LowStockAlertPersistenceConfig.SampleTicker ticker;

    @BeforeEach
    void clear() {
        jdbc.update("delete from operations.low_stock_alert_sku");
        jdbc.update("delete from operations.low_stock_alert_state");
        jdbc.update("delete from operations.notification");
        clock.now = NOW;
        ticker.now = 0;
    }

    TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    static LowStockAlertPolicy.Scan scan(Instant observedAt, Long... skuIds) {
        return new LowStockAlertPolicy.Scan(observedAt, Arrays.asList(skuIds), true);
    }

    List<Notification> accept(LowStockAlertPolicy.Scan scan) {
        return processor.accept(scan, RECIPIENTS, SETTINGS, ticker.now);
    }

    /** 다음 점검: 로컬 시계와 관측 시각을 함께 앞으로 민다. */
    List<Notification> next(Duration after, boolean complete, Long... skuIds) {
        clock.now = clock.now.plus(after);
        return processor.accept(new LowStockAlertPolicy.Scan(clock.now.minusSeconds(1), Arrays.asList(skuIds), complete), RECIPIENTS, SETTINGS,
            ticker.now);
    }

    List<Map<String, Object>> stateRows() {
        return jdbc.queryForList("select * from operations.low_stock_alert_state");
    }

    List<Map<String, Object>> skuRows() {
        return jdbc.queryForList("select * from operations.low_stock_alert_sku order by sku_id");
    }

    List<Map<String, Object>> notices() {
        return jdbc.queryForList("select * from operations.notification order by id");
    }

    List<Long> trackedIds() {
        return jdbc.queryForList("select sku_id from operations.low_stock_alert_sku order by sku_id", Long.class);
    }

    List<Long> missingIds() {
        return jdbc.queryForList("select sku_id from operations.low_stock_alert_sku where missing_since is not null order by sku_id", Long.class);
    }

    static Instant instant(Object value) {
        return switch (value) {
            case java.time.OffsetDateTime timestamp -> timestamp.toInstant();
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case Instant timestamp -> timestamp;
            default -> throw new AssertionError("Unexpected timestamp type: " + value.getClass().getName());
        };
    }

    @Test
    void firstScanStoresWatermarkTrackedSkusAndOneSummaryPerRecipient() {
        var saved = accept(scan(NOW.minusSeconds(2).plusNanos(1_999), 3L, 7L, 9L));
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(Notification::recipientMemberId).containsExactly(7L, 8L);
        assertThat(saved).allSatisfy(notice -> {
            assertThat(notice.kind()).isEqualTo(NotificationKind.LOW_STOCK);
            assertThat(notice.severity()).isEqualTo(NotificationSeverity.WARNING);
            assertThat(notice.sourceType()).isEqualTo("LOW_STOCK_ALERT");
            assertThat(notice.sourceId()).isEqualTo(notice.id());
            assertThat(notice.linkPath()).isEqualTo("/admin/stocks");
            assertThat(notice.title()).isEqualTo("품절 임박 상품 옵션 3개가 새로 생겼습니다. 재고를 확인해 주세요.");
            assertThat(notice.version()).isZero();
        });
        assertThat(notices()).hasSize(2);
        assertThat(trackedIds()).containsExactly(3L, 7L, 9L);
        assertThat(skuRows()).allSatisfy(row -> {
            assertThat(row.get("missing_since")).isNull();
            assertThat(row.get("version")).isEqualTo(0L);
            assertThat(row.get("created_at")).isNotNull();
        });
        var state = stateRows();
        assertThat(state).hasSize(1);
        assertThat(state.getFirst().get("source")).isEqualTo("PRODUCT");
        assertThat(instant(state.getFirst().get("last_observed_at"))).isEqualTo(NOW.minusSeconds(2).plusNanos(1_000));
        assertThat(instant(state.getFirst().get("last_accepted_local_at"))).isEqualTo(NOW);
        assertThat(instant(state.getFirst().get("last_alert_at"))).isEqualTo(NOW);
    }

    @Test
    void replayOrOlderObservationWritesNothing() {
        accept(scan(NOW.minusSeconds(2).plusNanos(1_999), 3L));
        var state = stateRows();
        var skus = skuRows();
        clock.now = NOW.plusSeconds(60);
        for (var observed : List.of(NOW.minusSeconds(2).plusNanos(1_000), NOW.minusSeconds(2).plusNanos(1_999), NOW.minusSeconds(3))) {
            assertThat(accept(scan(observed, 3L, 4L))).isEmpty();
            assertThat(stateRows()).isEqualTo(state);
            assertThat(skuRows()).isEqualTo(skus);
            assertThat(notices()).hasSize(2);
        }
    }

    @Test
    void sameLowSkusOnlyAdvanceWatermarkWithoutTouchingTrackedRowsOrNotifying() {
        accept(scan(NOW.minusSeconds(1), 3L, 7L));
        var skus = skuRows();
        assertThat(next(Duration.ofMinutes(10), true, 3L, 7L)).isEmpty();
        assertThat(skuRows()).isEqualTo(skus);
        var state = stateRows().getFirst();
        assertThat(state.get("version")).isEqualTo(1L);
        assertThat(instant(state.get("last_alert_at"))).isEqualTo(NOW);
        assertThat(instant(state.get("last_accepted_local_at"))).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(notices()).hasSize(2);
    }

    @Test
    void absenceIsMarkedOnlyOnCompleteScanAndClearedWhenSeenAgain() {
        accept(scan(NOW.minusSeconds(1), 3L, 7L));
        next(Duration.ofMinutes(10), false, 7L);
        assertThat(missingIds()).isEmpty();
        next(Duration.ofMinutes(10), true, 7L);
        assertThat(missingIds()).containsExactly(3L);
        assertThat(instant(jdbc.queryForObject("select missing_since from operations.low_stock_alert_sku where sku_id = 3", Object.class)))
            .isEqualTo(clock.now);
        assertThat(next(Duration.ofMinutes(10), true, 3L, 7L)).isEmpty();
        assertThat(missingIds()).isEmpty();
        assertThat(trackedIds()).containsExactly(3L, 7L);
        assertThat(notices()).hasSize(2);
    }

    @Test
    void absenceForResolveAfterEndsTrackingAndReentryAlertsAgain() {
        accept(scan(NOW.minusSeconds(1), 3L, 7L));
        next(Duration.ofMinutes(10), true, 7L);
        next(SETTINGS.resolveAfter().minusNanos(1_000), true, 7L);
        assertThat(trackedIds()).containsExactly(3L, 7L);
        next(Duration.ofNanos(1_000), true, 7L);
        assertThat(trackedIds()).containsExactly(7L);
        var again = next(Duration.ofMinutes(10), true, 3L, 7L);
        assertThat(again).hasSize(2).allSatisfy(notice -> {
            assertThat(notice.severity()).isEqualTo(NotificationSeverity.WARNING);
            assertThat(notice.title()).contains("1개가 새로");
        });
        assertThat(trackedIds()).containsExactly(3L, 7L);
    }

    @Test
    void reminderAfterIntervalIsOneInfoSummaryPerRecipient() {
        accept(scan(NOW.minusSeconds(1), 3L, 7L));
        assertThat(next(SETTINGS.reminderInterval().minusSeconds(1), true, 3L, 7L)).isEmpty();
        var reminder = next(Duration.ofSeconds(1), false, 3L, 7L);
        assertThat(reminder).hasSize(2).allSatisfy(notice -> {
            assertThat(notice.severity()).isEqualTo(NotificationSeverity.INFO);
            assertThat(notice.title()).isEqualTo("품절 임박 상품 옵션 2개 이상이 아직 남아 있습니다. 재고를 확인해 주세요.");
        });
        assertThat(instant(stateRows().getFirst().get("last_alert_at"))).isEqualTo(clock.now);
        assertThat(next(Duration.ofMinutes(10), true, 3L, 7L)).isEmpty();
    }

    @Test
    void scanOlderThanLockAtMostWritesNothing() {
        accept(scan(NOW.minusSeconds(1), 3L));
        var state = stateRows();
        clock.now = NOW.plusSeconds(600);
        // 점검 시작(0) 뒤 잠금까지 lockAtMost + 1ns 가 지났다.
        ticker.now = SETTINGS.lockAtMost().toNanos() + 1;
        assertThat(processor.accept(scan(NOW.plusSeconds(599), 3L, 4L), RECIPIENTS, SETTINGS, 0)).isEmpty();
        assertThat(stateRows()).isEqualTo(state);
        assertThat(trackedIds()).containsExactly(3L);
        assertThat(notices()).hasSize(2);
        // 정확히 lockAtMost 는 수락한다.
        ticker.now = SETTINGS.lockAtMost().toNanos();
        assertThat(processor.accept(scan(NOW.plusSeconds(599), 3L, 4L), RECIPIENTS, SETTINGS, 0)).hasSize(2);
        assertThat(trackedIds()).containsExactly(3L, 4L);
    }

    @Test
    void localClockRegressionWritesNothing() {
        accept(scan(NOW.minusSeconds(1), 3L));
        var state = stateRows();
        var skus = skuRows();
        clock.now = NOW.minusNanos(1_000);
        assertThat(processor.accept(scan(NOW.plusSeconds(10), 3L, 4L), RECIPIENTS, SETTINGS, ticker.now)).isEmpty();
        assertThat(stateRows()).isEqualTo(state);
        assertThat(skuRows()).isEqualTo(skus);
        assertThat(notices()).hasSize(2);
    }

    @Test
    void moreThanOneChunkOfTrackedRowsIsMarkedClearedAndResolved() {
        int count = LowStockAlertStateAdapter.CHUNK + 1;
        var all = LongStream.rangeClosed(1, count).boxed().toArray(Long[]::new);
        assertThat(accept(scan(NOW.minusSeconds(1), all))).hasSize(2).allSatisfy(notice ->
            assertThat(notice.title()).isEqualTo("품절 임박 상품 옵션 1,001개가 새로 생겼습니다. 재고를 확인해 주세요."));
        assertThat(trackedIds()).hasSize(count);
        next(Duration.ofMinutes(10), true);
        assertThat(missingIds()).hasSize(count);
        assertThat(next(Duration.ofMinutes(10), true, all)).isEmpty();
        assertThat(missingIds()).isEmpty();
        next(Duration.ofMinutes(10), true);
        assertThat(missingIds()).hasSize(count);
        next(SETTINGS.resolveAfter(), true);
        assertThat(trackedIds()).isEmpty();
        assertThat(notices()).hasSize(2);
    }

    @Test
    void failureAfterAllWritesRollsBackStateTrackedRowsAndNotifications() {
        accept(scan(NOW.minusSeconds(1), 3L));
        var state = stateRows();
        var skus = skuRows();
        var before = notices();
        clock.now = NOW.plusSeconds(600);
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            assertThat(accept(scan(NOW.plusSeconds(599), 4L, 5L))).hasSize(2);
            throw new IllegalStateException("synthetic failure after flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stateRows()).isEqualTo(state);
        assertThat(skuRows()).isEqualTo(skus);
        assertThat(notices()).isEqualTo(before);
    }

    @Test
    void invalidRecipientsFailBeforeAnyWrite() {
        for (var recipients : List.<List<Long>>of(List.of(), List.of(0L), List.of(7L, 7L), Arrays.asList(7L, null))) {
            assertThatThrownBy(() -> processor.accept(scan(NOW, 3L), recipients, SETTINGS, ticker.now))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(stateRows()).isEmpty();
        assertThat(skuRows()).isEmpty();
        assertThat(notices()).isEmpty();
    }

    @Test
    void stateUpdateRequiresLockInSameTransaction() {
        accept(scan(NOW.minusSeconds(1)));
        var state = new LowStockAlertPolicy.State(NOW, NOW, null);
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> states.updateLocked(state)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> states.findLocked()).isInstanceOf(RuntimeException.class);
    }
}
