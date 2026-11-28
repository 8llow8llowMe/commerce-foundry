package com.commercefoundry.domainlayer.settlement.domain.model;

import static com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType.AMOUNT;
import static com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType.CANCEL_TOTAL;
import static com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType.MISSING_IN_LEDGER;
import static com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType.MISSING_IN_PG;
import static com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType.STATUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 대사 판정 표 ({@code payment-guide.md} §10). 결제키 · 거래키는 형식만 흉내 낸 합성 값이다. */
class SettlementReconciliationTest {

    static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");
    static final String KEY = "tgen_settlement_judge_0001";
    /** 2026-11-20 10:00 KST. */
    static final Instant APPROVED_ON_SOLD_DATE = Instant.parse("2026-11-20T01:00:00Z");
    static final long PAYMENT_ID = 9_000_000_001L;

    @Nested
    @DisplayName("다섯 종류")
    class Types {

        @Test
        @DisplayName("AMOUNT — PG 승인합 ≠ 결제 금액 (기대 = 결제 금액, 실제 = PG 승인합)")
        void amount() {
            SettlementJudgement judgement = judge(List.of(approval("tx-1", 100_000)), ledger(129_000, 0, true), SOLD_DATE.plusDays(1));

            assertThat(judgement.detections()).containsExactly(new SettlementDetection(KEY, PAYMENT_ID, AMOUNT, 129_000, 100_000));
            assertThat(judgement.clearedTypes()).containsExactlyInAnyOrder(STATUS, MISSING_IN_PG, MISSING_IN_LEDGER, CANCEL_TOTAL);
        }

        @Test
        @DisplayName("STATUS — PG 승인 거래가 있는데 결제 기록이 결제됨 계열이 아니다 (기대 0, 실제 = PG 승인합)")
        void status() {
            SettlementJudgement judgement = judge(List.of(approval("tx-1", 129_000)), ledger(129_000, 0, false), SOLD_DATE.plusDays(1));

            assertThat(judgement.detections()).containsExactly(new SettlementDetection(KEY, PAYMENT_ID, STATUS, 0, 129_000));
        }

        @Test
        @DisplayName("CANCEL_TOTAL — PG 취소합 ≠ 원장 PG 취소 누계 (기대 = pgCanceled, 실제 = PG 취소합)")
        void cancelTotal() {
            SettlementJudgement judgement = judge(List.of(approval("tx-1", 129_000), cancel("tx-2", 30_000)), ledger(129_000, 29_000, true),
                SOLD_DATE.plusDays(1));

            assertThat(judgement.detections()).containsExactly(new SettlementDetection(KEY, PAYMENT_ID, CANCEL_TOTAL, 29_000, 30_000));
        }

        @Test
        @DisplayName("MISSING_IN_LEDGER — 결제 기록에 결제키가 없다 (기대 0, 실제 = PG 순액)")
        void missingInLedger() {
            SettlementJudgement judgement = judge(List.of(approval("tx-1", 129_000), cancel("tx-2", 29_000)), null, SOLD_DATE.plusDays(1));

            assertThat(judgement.detections()).containsExactly(new SettlementDetection(KEY, null, MISSING_IN_LEDGER, 0, 100_000));
            assertThat(judgement.evaluatedTypes()).containsExactly(MISSING_IN_LEDGER);
        }

        @Test
        @DisplayName("MISSING_IN_PG — 결제됨 계열 · 승인 시각이 매출일인데 PG 승인 거래가 없다 (기대 = 결제 금액, 실제 0)")
        void missingInPg() {
            SettlementJudgement judgement = judge(List.of(), ledger(129_000, 0, true), SOLD_DATE.plusDays(3));

            assertThat(judgement.detections()).containsExactly(new SettlementDetection(KEY, PAYMENT_ID, MISSING_IN_PG, 129_000, 0));
            assertThat(judgement.graceDeferred()).isFalse();
        }

        @Test
        @DisplayName("한 결제에 여러 종류가 함께 나온다 — 종류 순서로")
        void severalAtOnce() {
            SettlementJudgement judgement = judge(List.of(approval("tx-1", 100_000), cancel("tx-2", 10_000)), ledger(129_000, 0, false),
                SOLD_DATE.plusDays(1));

            assertThat(judgement.detections()).extracting(SettlementDetection::type).containsExactly(AMOUNT, STATUS, CANCEL_TOTAL);
            assertThat(judgement.clearedTypes()).containsExactlyInAnyOrder(MISSING_IN_PG, MISSING_IN_LEDGER);
        }
    }

    @Nested
    @DisplayName("PG 누락 유예 (매출일 + 3일)")
    class Grace {

        @Test
        @DisplayName("매출일 + 2일 — 기록하지 않고 유예로 센다. 판정하지 않았으니 기존 불일치를 닫지도 않는다")
        void twoDaysDeferred() {
            SettlementJudgement judgement = judge(List.of(), ledger(129_000, 0, true), SOLD_DATE.plusDays(2));

            assertThat(judgement.detections()).isEmpty();
            assertThat(judgement.graceDeferred()).isTrue();
            assertThat(judgement.evaluatedTypes()).doesNotContain(MISSING_IN_PG);
            assertThat(judgement.clearedTypes()).containsExactly(MISSING_IN_LEDGER);
        }

        @Test
        @DisplayName("매출일 + 3일 — 기록한다")
        void threeDaysRecorded() {
            assertThat(judge(List.of(), ledger(129_000, 0, true), SOLD_DATE.plusDays(3)).detections()).extracting(SettlementDetection::type)
                .containsExactly(MISSING_IN_PG);
        }

        @Test
        @DisplayName("승인 시각 경계 — KST 00:00 은 그 날, 다음 날 KST 00:00 은 다음 날이다")
        void approvedAtBoundary() {
            Instant startOfDay = Instant.parse("2026-11-19T15:00:00Z");
            Instant startOfNextDay = Instant.parse("2026-11-20T15:00:00Z");

            assertThat(judge(List.of(), ledger(129_000, 0, true, startOfDay), SOLD_DATE.plusDays(3)).detections()).hasSize(1);
            assertThat(judge(List.of(), ledger(129_000, 0, true, startOfNextDay), SOLD_DATE.plusDays(3)).detections()).isEmpty();
        }

        @Test
        @DisplayName("결제됨 계열이 아니면 PG 누락이 아니다")
        void notApprovedIsNotMissing() {
            SettlementJudgement judgement = judge(List.of(), ledger(129_000, 0, false, null), SOLD_DATE.plusDays(3));

            assertThat(judgement.detections()).isEmpty();
            assertThat(judgement.graceDeferred()).isFalse();
        }
    }

    @Nested
    @DisplayName("일치")
    class Matches {

        @Test
        @DisplayName("부분 취소 — 승인 129,000 · 취소 29,000 이 원장 금액 · PG 취소 누계와 맞는다")
        void partialCancel() {
            SettlementJudgement judgement = judge(List.of(approval("tx-1", 129_000), cancel("tx-2", 29_000)), ledger(129_000, 29_000, true),
                SOLD_DATE.plusDays(5));

            assertThat(judgement.detections()).isEmpty();
            assertThat(judgement.clearedTypes()).containsExactlyInAnyOrder(AMOUNT, STATUS, MISSING_IN_PG, MISSING_IN_LEDGER, CANCEL_TOTAL);
        }

        @Test
        @DisplayName("전체 취소 — 취소합 = 승인합 = PG 취소 누계")
        void fullCancel() {
            assertThat(judge(List.of(approval("tx-1", 129_000), cancel("tx-2", 129_000)), ledger(129_000, 129_000, true), SOLD_DATE.plusDays(5))
                .detections()).isEmpty();
        }

        @Test
        @DisplayName("다른 날 수집된 취소 거래도 결제키 누계에 합친다")
        void cancelsCollectedOnOtherDays() {
            List<PgSettlementItem> items = List.of(approval("tx-1", 129_000), cancel("tx-2", 20_000, SOLD_DATE.plusDays(1)),
                cancel("tx-3", 9_000, SOLD_DATE.plusDays(4)));

            assertThat(judge(items, ledger(129_000, 29_000, true), SOLD_DATE.plusDays(5)).detections()).isEmpty();
        }

        @Test
        @DisplayName("취소 거래만 수집됐으면(승인은 수집 범위 밖) 금액 · 상태 · 취소 판정을 미룬다")
        void onlyCancelCollectedDefersAmountJudgement() {
            SettlementJudgement judgement = judge(List.of(cancel("tx-2", 29_000)), ledger(129_000, 0, true, Instant.parse("2026-10-01T01:00:00Z")),
                SOLD_DATE.plusDays(5));

            assertThat(judgement.detections()).isEmpty();
            assertThat(judgement.evaluatedTypes()).containsExactly(MISSING_IN_LEDGER);
            assertThat(judgement.graceDeferred()).isFalse();
        }
    }

    @Test
    @DisplayName("다른 결제키의 거래 · 결제 기록이 섞이면 실패한다")
    void rejectsForeignKeys() {
        PgSettlementItem foreign = new PgSettlementItem("tx-9", KEY + "-other", null, SOLD_DATE, null, null, 1, 0, 0, CurrencyCode.KRW, false, null);

        assertThatThrownBy(() -> judge(List.of(foreign), null, SOLD_DATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementReconciliation.judge(KEY, List.of(),
            new SettlementLedgerPayment(PAYMENT_ID, KEY + "-other", 1, 0, CurrencyCode.KRW, true, APPROVED_ON_SOLD_DATE), SOLD_DATE, SOLD_DATE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static SettlementJudgement judge(List<PgSettlementItem> items, SettlementLedgerPayment ledger, LocalDate today) {
        return SettlementReconciliation.judge(KEY, items, ledger, SOLD_DATE, today);
    }

    static PgSettlementItem approval(String transactionKey, long amount) {
        return new PgSettlementItem(transactionKey, KEY, "9000000001", SOLD_DATE, null, SettlementPaymentMethod.CARD, amount, 0, 0, CurrencyCode.KRW, false,
            APPROVED_ON_SOLD_DATE);
    }

    static PgSettlementItem cancel(String transactionKey, long amount) {
        return cancel(transactionKey, amount, SOLD_DATE);
    }

    static PgSettlementItem cancel(String transactionKey, long amount, LocalDate soldDate) {
        return new PgSettlementItem(transactionKey, KEY, "9000000001", soldDate, null, SettlementPaymentMethod.CARD, -amount, 0, 0, CurrencyCode.KRW, true,
            APPROVED_ON_SOLD_DATE);
    }

    static SettlementLedgerPayment ledger(long amount, long pgCanceled, boolean approved) {
        return ledger(amount, pgCanceled, approved, approved ? APPROVED_ON_SOLD_DATE : null);
    }

    static SettlementLedgerPayment ledger(long amount, long pgCanceled, boolean approved, Instant approvedAt) {
        return new SettlementLedgerPayment(PAYMENT_ID, KEY, amount, pgCanceled, CurrencyCode.KRW, approved, approvedAt);
    }
}
