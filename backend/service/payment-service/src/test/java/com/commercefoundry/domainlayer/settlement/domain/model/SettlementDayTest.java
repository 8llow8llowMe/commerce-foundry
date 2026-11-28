package com.commercefoundry.domainlayer.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementDayTest {

    private static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final SettlementCollectionSummary SUMMARY = new SettlementCollectionSummary(3, 90_000, 2_700, 87_300, CurrencyCode.KRW);

    @Test
    @DisplayName("처음 수집 → COLLECTED, 요약 칸을 채우고 불일치 수 · 대사 시각은 비어 있다")
    void collectedFillsSummary() {
        SettlementDay day = SettlementDay.collected(1L, SOLD_DATE, SUMMARY, NOW);

        assertThat(day.status()).isEqualTo(SettlementDayStatus.COLLECTED);
        assertThat(day.itemCount()).isEqualTo(3);
        assertThat(day.pgAmountTotal()).isEqualTo(90_000);
        assertThat(day.feeTotal()).isEqualTo(2_700);
        assertThat(day.payOutTotal()).isEqualTo(87_300);
        assertThat(day.collectedAt()).isEqualTo(NOW);
        assertThat(day.reconciledAt()).isNull();
        assertThat(day.version()).isNull();
    }

    @Test
    @DisplayName("COLLECTED → RECONCILED 는 허용되고 불일치 수 · 대사 시각을 남긴다")
    void collectedCanReconcile() {
        SettlementDay reconciled = SettlementDay.collected(1L, SOLD_DATE, SUMMARY, NOW).reconcile(2, NOW.plusSeconds(5));

        assertThat(reconciled.status()).isEqualTo(SettlementDayStatus.RECONCILED);
        assertThat(reconciled.mismatchCount()).isEqualTo(2);
        assertThat(reconciled.reconciledAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("RECONCILED · FAILED 에서 바로 대사하면 거절한다 — 다시 수집(COLLECTED)부터")
    void reconcileRejectedOutsideCollected() {
        SettlementDay reconciled = SettlementDay.collected(1L, SOLD_DATE, SUMMARY, NOW).reconcile(0, NOW);
        SettlementDay failed = SettlementDay.failed(1L, SOLD_DATE, CurrencyCode.KRW);

        assertThatThrownBy(() -> reconciled.reconcile(0, NOW)).isInstanceOf(SettlementTransitionException.class)
            .hasMessageContaining("RECONCILED").hasMessageContaining("reconcile");
        assertThatThrownBy(() -> failed.reconcile(0, NOW)).isInstanceOf(SettlementTransitionException.class);
    }

    @Test
    @DisplayName("어느 상태에서든 다시 수집하면 COLLECTED — 요약은 새 값, 직전 불일치 수 · 대사 시각은 다음 대사까지 둔다")
    void recollectFromAnyStatus() {
        SettlementCollectionSummary next = new SettlementCollectionSummary(1, -10_000, -300, -9_700, CurrencyCode.KRW);
        SettlementDay reconciled = SettlementDay.collected(1L, SOLD_DATE, SUMMARY, NOW).reconcile(4, NOW);

        SettlementDay fromReconciled = reconciled.recollect(next, NOW.plusSeconds(60));
        SettlementDay fromFailed = SettlementDay.failed(1L, SOLD_DATE, CurrencyCode.KRW).recollect(next, NOW);

        assertThat(fromReconciled.status()).isEqualTo(SettlementDayStatus.COLLECTED);
        assertThat(fromReconciled.pgAmountTotal()).isEqualTo(-10_000);
        assertThat(fromReconciled.mismatchCount()).isEqualTo(4);
        assertThat(fromReconciled.reconciledAt()).isEqualTo(NOW);
        assertThat(fromFailed.status()).isEqualTo(SettlementDayStatus.COLLECTED);
        assertThat(fromFailed.collectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("수집 실패는 어느 상태에서든 FAILED — 요약 칸은 마지막 성공 값 그대로, 처음부터 실패면 0")
    void failKeepsLastSummary() {
        SettlementDay failedAfterReconcile = SettlementDay.collected(1L, SOLD_DATE, SUMMARY, NOW).reconcile(1, NOW).fail();
        SettlementDay firstFailure = SettlementDay.failed(2L, SOLD_DATE, CurrencyCode.KRW);

        assertThat(failedAfterReconcile.status()).isEqualTo(SettlementDayStatus.FAILED);
        assertThat(failedAfterReconcile.itemCount()).isEqualTo(3);
        assertThat(failedAfterReconcile.mismatchCount()).isEqualTo(1);
        assertThat(firstFailure.status()).isEqualTo(SettlementDayStatus.FAILED);
        assertThat(firstFailure.itemCount()).isZero();
        assertThat(firstFailure.collectedAt()).isNull();
        assertThat(firstFailure.fail().status()).isEqualTo(SettlementDayStatus.FAILED);
    }

    @Test
    @DisplayName("요약 통화가 다르면 다시 수집할 수 없다 · 상태별 시각이 없으면 만들 수 없다")
    void invariants() {
        SettlementDay day = SettlementDay.collected(1L, SOLD_DATE, SUMMARY, NOW);

        assertThatThrownBy(() -> day.recollect(new SettlementCollectionSummary(0, 0, 0, 0, CurrencyCode.USD), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementDay(1L, SOLD_DATE, SettlementDayStatus.COLLECTED, 0, 0, 0, 0, CurrencyCode.KRW, 0, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementDay(1L, SOLD_DATE, SettlementDayStatus.RECONCILED, 0, 0, 0, 0, CurrencyCode.KRW, 0, NOW, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementDay(1L, SOLD_DATE, SettlementDayStatus.FAILED, -1, 0, 0, 0, CurrencyCode.KRW, 0, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
