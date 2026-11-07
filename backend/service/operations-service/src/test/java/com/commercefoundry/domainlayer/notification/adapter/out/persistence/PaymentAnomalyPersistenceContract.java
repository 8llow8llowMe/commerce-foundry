package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.application.model.PaymentAnomalyObservationTiming;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertStatePort;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicySettings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** H2와 PG가 같은 실제 생산 저장 경계의 기능/계약/원자성 검증을 실행한다. */
abstract class PaymentAnomalyPersistenceContract extends AnomalyProbeSupport {
    static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");
    static final List<String> FAMILIES = Arrays.stream(PaymentAnomalyPolicy.Family.values()).map(Enum::name).sorted().toList();
    static final PaymentAnomalyPolicySettings SETTINGS = PaymentAnomalyPolicySettings.defaults();
    @Autowired PaymentAnomalyAlertStatePort states;
    @Autowired NotificationBatchInsertPort batch;
    @Autowired PaymentAnomalyAlertStateProcessor processor;
    @Autowired PaymentAnomalyPersistenceConfig.SampleClock clock;

    @Autowired @Qualifier("paymentAnomalyTicker") PaymentAnomalyPersistenceConfig.SampleTicker ticker;

    @BeforeEach
    void clearAnomalyState() {
        jdbc.update("delete from operations.payment_anomaly_alert_state");
        clock.now = NOW;
        ticker.now = 0;
        var env = new java.util.LinkedHashMap<String, Object>(environment);
        env.put("measurementScope", "E2 maximum sample/recipients prebuilt; policy/pending notifications/Entity/context/result inside transaction; "
            + "other synthetic fixtures built as invoked by action; validation queries excluded; explicit ends before commit/rollback");
        environment = Map.copyOf(env);
    }

    static PaymentAnomalyObservation sample(Instant at, boolean active) {
        var zero = new PaymentAnomalyObservation.Fact(0, null);
        var fact = active ? new PaymentAnomalyObservation.Fact(1, at.minusSeconds(3600)) : zero;
        return new PaymentAnomalyObservation(at, fact, zero, fact, fact, fact, fact);
    }

    List<Notification> accept(PaymentAnomalyObservation observation, List<Long> recipients, PaymentAnomalyPolicySettings settings) {
        return processor.accept(observation, recipients, settings, new PaymentAnomalyObservationTiming(clock.now, clock.now, ticker.now));
    }

    static PaymentAnomalyPolicy.State initial() { return PaymentAnomalyPolicy.accept(null, sample(NOW, false), NOW, SETTINGS).state(); }

    @Test
    void exactCollectionBudgetStillCommitsAllFiveStatesAndNotifications() {
        for (boolean existing : List.of(false, true)) {
            jdbc.update("delete from operations.payment_anomaly_alert_state");
            jdbc.update("delete from operations.notification");
            clock.now = NOW;
            ticker.now = 0;
            if (existing) {
                accept(sample(NOW.minusSeconds(1), false), List.of(1L), SETTINGS);
            }
            assertThat(stateRows()).hasSize(existing ? 5 : 0);
            var timing = new PaymentAnomalyObservationTiming(NOW.minusSeconds(30), NOW, -30_000_000_000L);
            var result = measure("E3_TIMING_BOUNDARY", 5, 1, () -> processor.accept(sample(NOW, true), List.of(1L), SETTINGS, timing));
            assertThat(result.failure()).isNull();
            assertThat(result.saved()).hasSize(5);
            assertSql(result.total(), 1, existing ? 0 : 5, existing ? 5 : 0, 5);
            assertThat(stateRows()).hasSize(5);
        }
    }

    @Test
    void invalidCollectionTimingNeverWritesInitialOrExistingStates() {
        for (boolean existing : List.of(false, true)) {
            jdbc.update("delete from operations.payment_anomaly_alert_state");
            clock.now = NOW;
            ticker.now = 0;
            if (existing) {
                accept(sample(NOW.minusSeconds(1), false), List.of(1L), SETTINGS);
            }
            var before = stateRows();
            assertThat(before).hasSize(existing ? 5 : 0);
            var noticesBefore = jdbc.queryForList("select * from operations.notification order by id");
            for (var timing : List.of(new PaymentAnomalyObservationTiming(NOW, NOW, -30_000_000_001L),
                new PaymentAnomalyObservationTiming(NOW, NOW, 1), new PaymentAnomalyObservationTiming(NOW, NOW.minusNanos(1), 0),
                new PaymentAnomalyObservationTiming(NOW, NOW.plusNanos(1), 0))) {
                var result = measure("E3_TIMING_REJECTED", 0, 1, () -> processor.accept(sample(NOW, true), List.of(1L), SETTINGS, timing));
                assertThat(result.failure()).isNull();
                assertThat(result.saved()).isEmpty();
                assertSql(result.total(), 1, 0, 0, 0);
                assertThat(stateRows()).isEqualTo(before);
                assertThat(jdbc.queryForList("select * from operations.notification order by id")).isEqualTo(noticesBefore);
            }
        }
    }

    List<Map<String, Object>> stateRows() {
        return jdbc.queryForList("select * from operations.payment_anomaly_alert_state order by family");
    }

    private static Instant auditInstant(Object value) {
        return switch (value) {
            case java.time.OffsetDateTime timestamp -> timestamp.toInstant();
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            default -> throw new AssertionError("Unexpected audit timestamp type: " + value.getClass().getName());
        };
    }

    @Test
    void firstNormalPersistsFiveSortedStatesWithoutNotificationsAndReplayDoesNotWrite() {
        var first = measure("E2_FIRST_NORMAL", 0, 1, () -> accept(sample(NOW, false), List.of(1L), SETTINGS));
        assertThat(first.failure()).isNull();
        assertSql(first.total(), 1, 5, 0, 0);
        assertThat(first.total().get("stateInsertFamilyOrder")).isEqualTo(FAMILIES);
        var before = stateRows();
        assertThat(before).hasSize(5);
        assertThat(before.stream().map(row -> row.get("family"))).containsExactlyElementsOf(FAMILIES);
        assertThat(before).allSatisfy(row -> {
            assertThat(row.get("version")).isEqualTo(0L);
            assertThat(row.get("created_at")).isNotNull();
            assertThat(row.get("updated_at")).isNotNull();
        });
        for (var at : List.of(NOW, NOW.minusSeconds(1))) {
            var replay = measure("E2_REPLAY", 0, 1, () -> accept(sample(at, true), List.of(1L), SETTINGS));
            assertThat(replay.failure()).isNull();
            assertSql(replay.total(), 1, 0, 0, 0);
            assertThat(stateRows()).isEqualTo(before);
        }
    }

    @Test
    void existingManagedStatesUpdateWithoutExtraSelectAndPreserveCreatedAudit() {
        accept(sample(NOW, false), List.of(1L), SETTINGS);
        var before = stateRows();
        clock.now = NOW.plusSeconds(1);
        var result = measure("E2_EXISTING", 5, 1, () -> accept(sample(clock.now, true), List.of(1L), SETTINGS));
        assertThat(result.failure()).isNull();
        assertSql(result.total(), 1, 0, 5, 5);
        assertThat(result.saved()).hasSize(5).allSatisfy(n -> {
            assertThat(n.sourceType()).isEqualTo("PAYMENT_ANOMALY_ALERT");
            assertThat(n.sourceId()).isEqualTo(n.id());
            assertThat(n.linkPath()).isEqualTo("/admin/payments");
            assertThat(n.version()).isZero();
            assertThat(n.createdAt()).isNotNull();
        });
        var after = stateRows();
        for (int i = 0; i < 5; i++) {
            assertThat(after.get(i).get("created_at")).isEqualTo(before.get(i).get("created_at"));
            assertThat(after.get(i).get("version")).isEqualTo(1L);
            // 서로 다른 쓰기도 DB의 마이크로초 시각에서는 같은 값일 수 있다. 버전 증가가 실제 갱신을 확인한다.
            assertThat(auditInstant(after.get(i).get("updated_at")))
                .isAfterOrEqualTo(auditInstant(before.get(i).get("updated_at")));
        }
        assertThatThrownBy(() -> result.saved().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void freshSourceWithLocalClockReversalStaleAndFutureSamplesDoNotWrite() {
        clock.now = NOW.plusSeconds(10);
        accept(sample(NOW, false), List.of(1L), SETTINGS);
        var before = stateRows();
        for (long[] seconds : List.of(new long[] {1, 9}, new long[] {1, 60}, new long[] {11, 10})) {
            clock.now = NOW.plusSeconds(seconds[1]);
            var result = measure("E2_REJECTED_TIMING", 0, 1,
                () -> accept(sample(NOW.plusSeconds(seconds[0]), true), List.of(1L), SETTINGS));
            assertThat(result.failure()).isNull();
            assertSql(result.total(), 1, 0, 0, 0);
            assertThat(stateRows()).isEqualTo(before);
        }
    }

    @Test
    void batchValidatesWholeInputAndRequiresCallerTransaction() {
        var fresh = AnomalyProbeStore.fixture(1).getFirst();
        assertThatThrownBy(() -> batch.insertAll(List.of(fresh))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        for (var invalid : List.of(List.<Notification>of(), List.of(fresh, fresh), AnomalyProbeStore.fixture(5001),
            List.of(fresh, fresh.markRead(NOW)), List.of(fresh, copy(fresh, NOW, null)), List.of(fresh, copy(fresh, null, 0L)))) {
            var result = measure("E2_INVALID_BATCH", 0, 1, () -> batch.insertAll(invalid));
            assertThat(result.failure()).isInstanceOf(IllegalArgumentException.class);
            assertSql(result.total(), 0, 0, 0, 0);
        }
        var nullItem = new ArrayList<Notification>();
        nullItem.add(fresh);
        nullItem.add(null);
        assertThat(measure("E2_NULL_ITEM", 0, 1, () -> batch.insertAll(nullItem)).failure()).isInstanceOf(IllegalArgumentException.class);
        assertThat(measure("E2_NULL_BATCH", 0, 1, () -> batch.insertAll(null)).failure()).isInstanceOf(IllegalArgumentException.class);
        var badTitle = new Notification(fresh.id() + 1, fresh.recipientMemberId(), fresh.kind(), fresh.severity(), "\n", fresh.linkPath(),
            fresh.sourceType(), fresh.sourceId() + 1, null, null, null);
        var invalidTitle = measure("E2_INVALID_BATCH_TITLE", 0, 1, () -> batch.insertAll(List.of(fresh, badTitle)));
        assertThat(invalidTitle.failure()).isInstanceOf(IllegalArgumentException.class);
        assertSql(invalidTitle.total(), 0, 0, 0, 0);
        var input = AnomalyProbeStore.fixture(3);
        var valid = measure("E2_BATCH", 3, 1, () -> batch.insertAll(input));
        assertThat(valid.failure()).isNull();
        assertSql(valid.total(), 0, 0, 0, 3);
        assertThat(valid.saved()).hasSize(3).allSatisfy(n -> assertThat(n.createdAt()).isNotNull());
        assertMapping(input, valid.saved());
    }

    @Test
    void recipientsAreStrictAndNotTruncated() {
        var nullable = new ArrayList<Long>();
        nullable.add(null);
        for (var invalid : List.of(List.<Long>of(), List.of(0L), List.of(-1L), List.of(1L, 1L), nullable,
            LongStream.rangeClosed(1, 1001).boxed().toList())) {
            var result = measure("E2_INVALID_RECIPIENTS", 0, 1, () -> accept(sample(NOW, true), invalid, SETTINGS));
            assertThat(result.failure()).isInstanceOf(IllegalArgumentException.class);
            assertSql(result.total(), 0, 0, 0, 0);
        }
        assertThatThrownBy(() -> accept(sample(NOW, true), null, SETTINGS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialRowsAndMismatchedWatermarksFailClosedWithoutRepair() {
        for (int remaining = 1; remaining < 5; remaining++) {
            jdbc.update("delete from operations.payment_anomaly_alert_state");
            accept(sample(NOW, false), List.of(1L), SETTINGS);
            for (String family : FAMILIES.subList(remaining, 5)) {
                jdbc.update("delete from operations.payment_anomaly_alert_state where family = ?", family);
            }
            assertCorruptUnchanged();
        }
        jdbc.update("delete from operations.payment_anomaly_alert_state");
        accept(sample(NOW, false), List.of(1L), SETTINGS);
        jdbc.update("update operations.payment_anomaly_alert_state set last_observed_at = ? where family = ?",
            java.sql.Timestamp.from(NOW.minusSeconds(1)), FAMILIES.getFirst());
        assertCorruptUnchanged();
        jdbc.update("update operations.payment_anomaly_alert_state set last_observed_at = ?, last_accepted_local_at = ? where family = ?",
            java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(1)), FAMILIES.getFirst());
        assertCorruptUnchanged();
    }

    private void assertCorruptUnchanged() {
        var before = stateRows();
        clock.now = NOW.plusSeconds(2);
        var result = measure("E2_CORRUPTION", 0, 1, () -> accept(sample(clock.now, true), List.of(1L), SETTINGS));
        assertThat(result.failure()).isNotNull();
        assertSql(result.total(), 1, 0, 0, 0);
        assertThat(stateRows()).isEqualTo(before);
    }

    @Test
    void updateWithoutLoadedWriteLockFailsWithoutAdditionalSelect() {
        accept(sample(NOW, false), List.of(1L), SETTINGS);
        var before = stateRows();
        var noLoad = measure("E2_UNLOADED_UPDATE", 0, 1, () -> { states.updateLocked(initial()); return List.of(); });
        assertThat(noLoad.failure()).isInstanceOf(IllegalStateException.class);
        assertSql(noLoad.total(), 0, 0, 0, 0);
        var noLock = measure("E2_UNLOCKED_UPDATE", 0, 1, () -> {
            entityManager.createQuery("select s from PaymentAnomalyAlertStateEntity s").getResultList();
            states.updateLocked(initial());
            return List.of();
        });
        assertThat(noLock.failure()).isInstanceOf(IllegalStateException.class);
        assertSql(noLock.total(), 1, 0, 0, 0);
        assertThat(stateRows()).isEqualTo(before);
    }

    @Test
    void actualStateFlushAndNotificationWritesRollBackTogether() {
        for (boolean existing : List.of(false, true)) {
            jdbc.update("delete from operations.payment_anomaly_alert_state");
            jdbc.update("delete from operations.notification");
            if (existing) {
                accept(sample(NOW, true), List.of(1L), SETTINGS);
            }
            var before = stateRows();
            var noticesBefore = jdbc.queryForList("select * from operations.notification order by id");
            clock.now = NOW.plusSeconds(1);
            var flushed = measure("E2_STATE_FLUSH_ROLLBACK", 0, 1, () -> {
                accept(sample(clock.now, false), List.of(1L), SETTINGS);
                throw new IllegalStateException("synthetic post-state-flush failure");
            });
            assertThat(flushed.failure()).isInstanceOf(IllegalStateException.class);
            assertThat(AnomalyProbeMeter.value(flushed.explicit(), existing ? "stateUpdateRows" : "stateInsertRows")).isEqualTo(5);
            assertThat(stateRows()).isEqualTo(before);
            assertThat(jdbc.queryForList("select * from operations.notification order by id")).isEqualTo(noticesBefore);
            var input = new ArrayList<>(AnomalyProbeStore.fixture(4));
            var duplicate = input.get(2);
            var first = input.getFirst();
            input.set(2, new Notification(duplicate.id(), duplicate.recipientMemberId(), duplicate.kind(), duplicate.severity(), duplicate.title(),
                duplicate.linkPath(), first.sourceType(), first.sourceId(), null, null, null));
            var conflict = measure("E2_MID_NOTIFICATION_UNIQUE", 4, 1, () -> {
                accept(sample(clock.now, false), List.of(1L), SETTINGS);
                batch.insertAll(input.subList(0, 2));
                return batch.insertAll(input.subList(2, 4));
            });
            assertThat(conflict.failure()).isNotNull();
            assertThat(AnomalyProbeMeter.value(conflict.explicit(), "insertRows")).isGreaterThanOrEqualTo(2);
            assertThat(stateRows()).isEqualTo(before);
            assertThat(jdbc.queryForList("select * from operations.notification order by id")).isEqualTo(noticesBefore);
        }
    }

    static void assertSql(Map<String, Object> metrics, long selects, long stateInserts, long stateUpdates, long notices) {
        assertThat(AnomalyProbeMeter.value(metrics, "stateSelectAttemptRows")).isEqualTo(selects);
        assertThat(AnomalyProbeMeter.value(metrics, "stateInsertAttemptRows")).isEqualTo(stateInserts);
        assertThat(AnomalyProbeMeter.value(metrics, "stateUpdateAttemptRows")).isEqualTo(stateUpdates);
        assertThat(AnomalyProbeMeter.value(metrics, "selectAttemptRows")).isZero();
        assertThat(AnomalyProbeMeter.value(metrics, "insertAttemptRows")).isEqualTo(notices);
        assertThat(AnomalyProbeMeter.value(metrics, "updateAttemptRows")).isZero();
        assertThat(AnomalyProbeMeter.value(metrics, "otherAttemptRows")).isZero();
        assertThat(AnomalyProbeMeter.value(metrics, "stateSelectRows")).isEqualTo(selects);
        assertThat(AnomalyProbeMeter.value(metrics, "stateInsertRows")).isEqualTo(stateInserts);
        assertThat(AnomalyProbeMeter.value(metrics, "stateUpdateRows")).isEqualTo(stateUpdates);
        assertThat(AnomalyProbeMeter.value(metrics, "selectRows")).isZero();
        assertThat(AnomalyProbeMeter.value(metrics, "insertRows")).isEqualTo(notices);
        assertThat(AnomalyProbeMeter.value(metrics, "updateRows")).isZero();
        assertThat(AnomalyProbeMeter.value(metrics, "otherRows")).isZero();
    }

    private static Notification copy(Notification n, Instant created, Long version) {
        return new Notification(n.id(), n.recipientMemberId(), n.kind(), n.severity(), n.title(), n.linkPath(), n.sourceType(), n.sourceId(), null, created, version);
    }
}
