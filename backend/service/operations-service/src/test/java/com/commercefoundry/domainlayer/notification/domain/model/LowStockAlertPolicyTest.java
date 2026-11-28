package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy.Notice;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy.Page;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy.Rejection;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy.Scan;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy.Settings;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy.State;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy.Tracked;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowStockAlertPolicyTest {
    static final Instant NOW = Instant.parse("2026-11-28T01:00:00Z");
    static final Settings SETTINGS = Settings.defaults();
    static final Duration OK = Duration.ofSeconds(1);

    static Scan complete(Instant observedAt, Long... ids) {
        return new Scan(observedAt, Arrays.asList(ids), true);
    }

    static State state(Instant observedAt, Instant alertAt) {
        return new State(observedAt, NOW, alertAt);
    }

    @Test
    @DisplayName("기본 설정은 20페이지 · 해소 6시간 · 재알림 7일 · 상한 5분이고 범위 밖 값은 거부한다")
    void settings() {
        assertThat(SETTINGS).isEqualTo(new Settings(20, Duration.ofHours(6), Duration.ofDays(7), Duration.ofMinutes(5)));
        assertThat(new Settings(LowStockAlertPolicy.MAX_PAGES_LIMIT, OK, OK, OK).maxPages()).isEqualTo(40);
        for (int pages : new int[] {0, -1, 41}) {
            assertThatThrownBy(() -> new Settings(pages, OK, OK, OK)).isInstanceOf(IllegalArgumentException.class);
        }
        for (var bad : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> new Settings(1, bad, OK, OK)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Settings(1, OK, bad, OK)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Settings(1, OK, OK, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Settings(1, null, OK, OK)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("페이지는 요청 커서보다 큰 양수 오름차순 · 100개 이하이고 다음 커서는 hasNext 일 때 마지막 SKU 와 같아야 한다")
    void pageContract() {
        assertThat(new Page(0, NOW, List.of(1L, 5L), true, 5L).nextAfterSkuId()).isEqualTo(5L);
        assertThat(new Page(0, NOW, List.of(), false, null).skuIds()).isEmpty();
        assertThat(new Page(4, NOW, List.of(5L), false, null).afterSkuId()).isEqualTo(4L);
        for (var invalid : List.<Runnable>of(
            () -> new Page(0, NOW, List.of(2L, 1L), false, null),
            () -> new Page(0, NOW, List.of(1L, 1L), false, null),
            () -> new Page(0, NOW, List.of(0L), false, null),
            () -> new Page(0, NOW, List.of(-1L), false, null),
            () -> new Page(0, NOW, Arrays.asList(1L, null), false, null),
            () -> new Page(0, NOW, List.of(1L), true, null),
            () -> new Page(0, NOW, List.of(1L, 2L), true, 1L),
            () -> new Page(0, NOW, List.of(), true, 1L),
            () -> new Page(0, NOW, List.of(1L), false, 1L),
            () -> new Page(5, NOW, List.of(5L), false, null),
            () -> new Page(5, NOW, List.of(3L, 6L), false, null),
            () -> new Page(-1, NOW, List.of(), false, null),
            () -> new Page(0, NOW, LongStream.rangeClosed(1, 101).boxed().toList(), false, null))) {
            assertThatThrownBy(invalid::run).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> new Page(0, null, List.of(), false, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("페이지를 묶을 때 첫 페이지 관측 시각을 마이크로초로 내리고 마지막 페이지 hasNext 로 완전 스캔을 정한다")
    void scanOfPages() {
        var first = new Page(0, NOW.plusNanos(1_999), List.of(1L, 2L), true, 2L);
        var last = new Page(2, NOW.plusSeconds(5), List.of(3L), false, null);
        var scan = Scan.of(List.of(first, last));
        assertThat(scan.observedAt()).isEqualTo(NOW.plusNanos(1_000));
        assertThat(scan.skuIds()).containsExactly(1L, 2L, 3L);
        assertThat(scan.complete()).isTrue();
        var truncated = Scan.of(List.of(first));
        assertThat(truncated.complete()).isFalse();
        assertThat(Scan.of(List.of(new Page(0, NOW, List.of(), false, null))).skuIds()).isEmpty();
        assertThat(Scan.of(List.of(first, new Page(2, NOW, List.of(), false, null))).complete()).isTrue();
    }

    @Test
    @DisplayName("끝난 뒤 이어진 페이지 · 커서 앞으로 돌아간 페이지 · 빈 목록 · 40페이지 초과는 계약 위반이다")
    void scanOfPagesRejectsBrokenCursor() {
        var first = new Page(0, NOW, List.of(1L, 2L), true, 2L);
        var end = new Page(0, NOW, List.of(1L), false, null);
        assertThatThrownBy(() -> Scan.of(List.of(end, new Page(1, NOW, List.of(5L), false, null)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scan.of(List.of(first, new Page(1, NOW, List.of(2L), false, null)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scan.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        var pages = LongStream.rangeClosed(1, 41).mapToObj(id -> new Page(id - 1, NOW, List.of(id), true, id)).toList();
        assertThatThrownBy(() -> Scan.of(pages)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Scan.of(pages.subList(0, 40)).complete()).isFalse();
    }

    @Test
    @DisplayName("첫 페이지는 커서 0 에서, 뒤 페이지는 앞 페이지의 다음 커서에서 요청한 것이어야 한다(건너뜀 · 되감기 거부)")
    void scanOfPagesRequiresCursorContinuity() {
        var first = new Page(0, NOW, List.of(1L, 2L), true, 2L);
        // 첫 페이지가 0 이 아닌 커서에서 시작: 앞쪽 SKU 를 보지 않았으므로 완전 스캔이라 할 수 없다.
        assertThatThrownBy(() -> Scan.of(List.of(new Page(1, NOW, List.of(2L), false, null)))).isInstanceOf(IllegalArgumentException.class);
        // 다음 커서(2)를 건너뛰어 5 뒤에서 요청: 3~5 가 빠진다.
        assertThatThrownBy(() -> Scan.of(List.of(first, new Page(5, NOW, List.of(6L), false, null)))).isInstanceOf(IllegalArgumentException.class);
        // 다음 커서보다 앞(0)으로 되감아 요청.
        assertThatThrownBy(() -> Scan.of(List.of(first, new Page(0, NOW, List.of(3L), false, null)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Scan.of(List.of(first, new Page(2, NOW, List.of(3L), false, null))).skuIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("처음 점검: 새 SKU 를 추적에 넣고 새 건수로 WARNING 알림, 워터마크를 만든다")
    void firstScanAlertsNewSkus() {
        var decision = LowStockAlertPolicy.decide(null, List.of(), complete(NOW.minusSeconds(3), 3L, 7L), NOW.plusNanos(999), OK, SETTINGS);
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.notice()).isEqualTo(Notice.NEW_LOW_STOCK);
        assertThat(decision.notice().severity()).isEqualTo(NotificationSeverity.WARNING);
        assertThat(decision.count()).isEqualTo(2);
        assertThat(decision.changes().inserted()).containsExactly(3L, 7L);
        assertThat(decision.changes().at()).isEqualTo(NOW);
        assertThat(decision.state()).isEqualTo(new State(NOW.minusSeconds(3), NOW, NOW));
    }

    @Test
    @DisplayName("처음 점검이 비어 있으면 알림 없이 워터마크만 만든다")
    void firstEmptyScanOnlyCreatesWatermark() {
        var decision = LowStockAlertPolicy.decide(null, List.of(), complete(NOW), NOW, OK, SETTINGS);
        assertThat(decision.notice()).isEqualTo(Notice.NONE);
        assertThat(decision.changes().isEmpty()).isTrue();
        assertThat(decision.state()).isEqualTo(new State(NOW, NOW, null));
    }

    @Test
    @DisplayName("계속 임박인 SKU 는 추적 행을 건드리지 않고 재알림 간격 전에는 알리지 않는다")
    void stillLowWithinReminderDoesNothing() {
        var previous = state(NOW.minusSeconds(600), NOW.minus(Duration.ofDays(1)));
        var decision = LowStockAlertPolicy.decide(previous, List.of(new Tracked(3, null)), complete(NOW, 3L), NOW.plusSeconds(1), OK, SETTINGS);
        assertThat(decision.notice()).isEqualTo(Notice.NONE);
        assertThat(decision.changes().isEmpty()).isTrue();
        assertThat(decision.state().lastAlertAt()).isEqualTo(previous.lastAlertAt());
        assertThat(decision.state().lastObservedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("새 SKU 없이 임박이 남아 있고 재알림 간격이 지나면 본 SKU 수로 INFO 재알림 1건")
    void reminderAfterInterval() {
        var previous = state(NOW.minusSeconds(600), NOW.minus(SETTINGS.reminderInterval()));
        var tracked = List.of(new Tracked(3, null), new Tracked(4, null));
        var decision = LowStockAlertPolicy.decide(previous, tracked, complete(NOW, 3L, 4L), NOW, OK, SETTINGS);
        assertThat(decision.notice()).isEqualTo(Notice.STILL_LOW_STOCK);
        assertThat(decision.notice().severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(decision.count()).isEqualTo(2);
        assertThat(decision.state().lastAlertAt()).isEqualTo(NOW);
        var early = LowStockAlertPolicy.decide(state(NOW.minusSeconds(600), NOW.minus(SETTINGS.reminderInterval()).plusNanos(1_000)), tracked,
            complete(NOW, 3L, 4L), NOW, OK, SETTINGS);
        assertThat(early.notice()).isEqualTo(Notice.NONE);
        var empty = LowStockAlertPolicy.decide(previous, List.of(), complete(NOW), NOW, OK, SETTINGS);
        assertThat(empty.notice()).isEqualTo(Notice.NONE);
    }

    @Test
    @DisplayName("새 SKU 가 섞이면 재알림이 아니라 새 건수만 WARNING 으로 알린다")
    void newSkuWinsOverReminder() {
        var previous = state(NOW.minusSeconds(600), NOW.minus(Duration.ofDays(30)));
        var decision = LowStockAlertPolicy.decide(previous, List.of(new Tracked(3, null)), complete(NOW, 3L, 9L), NOW, OK, SETTINGS);
        assertThat(decision.notice()).isEqualTo(Notice.NEW_LOW_STOCK);
        assertThat(decision.count()).isEqualTo(1);
        assertThat(decision.changes().inserted()).containsExactly(9L);
    }

    @Test
    @DisplayName("완전 스캔에서 처음 빠지면 표시만, 해소 기간 전 다시 빠져도 유지, 기간 이상이면 추적을 끝낸다")
    void absenceNeedsResolveAfter() {
        var previous = state(NOW.minusSeconds(600), NOW.minusSeconds(600));
        var first = LowStockAlertPolicy.decide(previous, List.of(new Tracked(3, null)), complete(NOW), NOW, OK, SETTINGS);
        assertThat(first.changes().missing()).containsExactly(3L);
        assertThat(first.changes().resolved()).isEmpty();
        assertThat(first.changes().at()).isEqualTo(NOW);
        var since = NOW.minus(SETTINGS.resolveAfter());
        var waiting = LowStockAlertPolicy.decide(previous, List.of(new Tracked(3, since.plusNanos(1_000))), complete(NOW), NOW, OK, SETTINGS);
        assertThat(waiting.changes().isEmpty()).isTrue();
        var resolved = LowStockAlertPolicy.decide(previous, List.of(new Tracked(3, since), new Tracked(1, since)), complete(NOW), NOW, OK, SETTINGS);
        assertThat(resolved.changes().resolved()).containsExactly(1L, 3L);
        assertThat(resolved.notice()).isEqualTo(Notice.NONE);
    }

    @Test
    @DisplayName("다시 보인 SKU 는 부재 표시만 지우고 알리지 않는다")
    void reappearedClearsMissing() {
        var previous = state(NOW.minusSeconds(600), NOW.minusSeconds(600));
        var decision = LowStockAlertPolicy.decide(previous, List.of(new Tracked(3, NOW.minusSeconds(60))), complete(NOW, 3L), NOW, OK, SETTINGS);
        assertThat(decision.changes().reappeared()).containsExactly(3L);
        assertThat(decision.notice()).isEqualTo(Notice.NONE);
    }

    @Test
    @DisplayName("잘린 점검은 본 SKU 만 반영하고 부재 판정 · 추적 해제를 건너뛴다")
    void truncatedScanSkipsAbsence() {
        var previous = state(NOW.minusSeconds(600), NOW.minusSeconds(600));
        var since = NOW.minus(Duration.ofDays(1));
        var tracked = List.of(new Tracked(3, null), new Tracked(4, since), new Tracked(5, since));
        var decision = LowStockAlertPolicy.decide(previous, tracked, new Scan(NOW, List.of(5L, 8L), false), NOW, OK, SETTINGS);
        assertThat(decision.complete()).isFalse();
        assertThat(decision.changes().missing()).isEmpty();
        assertThat(decision.changes().resolved()).isEmpty();
        assertThat(decision.changes().reappeared()).containsExactly(5L);
        assertThat(decision.changes().inserted()).containsExactly(8L);
    }

    @Test
    @DisplayName("관측 시각이 워터마크보다 늦지 않으면(마이크로초 내림 뒤 같음 포함) 거부한다")
    void staleObservationRejected() {
        var previous = state(NOW, null);
        for (var observed : List.of(NOW, NOW.minusSeconds(1), NOW.plusNanos(999))) {
            var decision = LowStockAlertPolicy.decide(previous, List.of(), complete(observed, 1L), NOW.plusSeconds(1), OK, SETTINGS);
            assertThat(decision.accepted()).isFalse();
            assertThat(decision.rejection()).isEqualTo(Rejection.STALE_OBSERVATION);
            assertThat(decision.state()).isNull();
        }
        assertThat(LowStockAlertPolicy.decide(previous, List.of(), complete(NOW.plusNanos(1_000), 1L), NOW, OK, SETTINGS).accepted()).isTrue();
    }

    @Test
    @DisplayName("로컬 시계가 마지막 수락보다 뒤로 가면 거부한다")
    void localClockRegressionRejected() {
        var previous = state(NOW.minusSeconds(60), null);
        var decision = LowStockAlertPolicy.decide(previous, List.of(), complete(NOW), NOW.minusNanos(1_000), OK, SETTINGS);
        assertThat(decision.rejection()).isEqualTo(Rejection.LOCAL_CLOCK_REGRESSED);
    }

    @Test
    @DisplayName("점검 경과가 음수이거나 lockAtMost 를 넘으면 거부하고, 같으면 수락한다")
    void lockExpiredRejected() {
        for (var elapsed : List.of(Duration.ofNanos(-1), SETTINGS.lockAtMost().plusNanos(1))) {
            assertThat(LowStockAlertPolicy.decide(null, List.of(), complete(NOW, 1L), NOW, elapsed, SETTINGS).rejection())
                .isEqualTo(Rejection.LOCK_EXPIRED);
        }
        assertThat(LowStockAlertPolicy.decide(null, List.of(), complete(NOW, 1L), NOW, SETTINGS.lockAtMost(), SETTINGS).accepted()).isTrue();
    }

    @Test
    @DisplayName("상태 값 · 추적 행이 깨졌으면 판정하지 않는다")
    void invalidStoredValues() {
        assertThatThrownBy(() -> new State(NOW, NOW, NOW.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Tracked(0, null)).isInstanceOf(IllegalArgumentException.class);
        var duplicate = List.of(new Tracked(1, null), new Tracked(1, NOW));
        assertThatThrownBy(() -> LowStockAlertPolicy.decide(null, duplicate, complete(NOW), NOW, OK, SETTINGS))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scan(NOW, List.of(2L, 1L), true)).isInstanceOf(IllegalArgumentException.class);
    }
}
