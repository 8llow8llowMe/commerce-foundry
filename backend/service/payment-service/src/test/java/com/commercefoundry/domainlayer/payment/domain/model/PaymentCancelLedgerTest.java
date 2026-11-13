package com.commercefoundry.domainlayer.payment.domain.model;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel.Status;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 취소 원장 합계 (#693, {@code payment-guide.md} §8-1) — pgCanceled · refundedTotal 의 분리와 상태별 의미. */
class PaymentCancelLedgerTest {

    private static PaymentCancel row(long claimId, long amount, Status status) {
        return new PaymentCancel(claimId, PAYMENT_ID, krw(amount), status);
    }

    @Test
    @DisplayName("pgCanceled 는 SUCCEEDED + EXTERNAL_PG_REFUNDED, refundedTotal 은 여기에 OFF_PG_REFUNDED 를 더한다 — PENDING · REJECTED · VOIDED 는 어디에도 없다")
    void sumsByStatus() {
        PaymentCancelLedger ledger = PaymentCancelLedger.of(List.of(
            row(41, 10_000, Status.SUCCEEDED),
            row(42, 20_000, Status.EXTERNAL_PG_REFUNDED),
            row(43, 5_000, Status.OFF_PG_REFUNDED),
            row(44, 1_000, Status.PENDING),
            row(45, 2_000, Status.REJECTED),
            row(46, 3_000, Status.VOIDED)), CurrencyCode.KRW);

        assertThat(ledger.pgCanceled()).isEqualTo(krw(30_000));
        assertThat(ledger.refundedTotal()).isEqualTo(krw(35_000));
        assertThat(ledger.offPgRefunded()).isEqualTo(krw(5_000));
        assertThat(ledger.hasOffPgRefund()).isTrue();
        assertThat(ledger.expectedPgBalance(krw(129_000))).as("PG 기대 잔액은 OFF_PG 를 빼지 않는다").isEqualTo(krw(99_000));
        assertThat(ledger.retained(krw(129_000))).as("남은 금액은 OFF_PG 까지 뺀다").isEqualTo(krw(94_000));
    }

    @Test
    @DisplayName("행이 없으면 두 합계가 0 이고, PG 밖 환불이 없으면 두 합계가 같다")
    void emptyAndPgOnly() {
        PaymentCancelLedger empty = PaymentCancelLedger.of(List.of(), CurrencyCode.KRW);
        assertThat(empty).isEqualTo(new PaymentCancelLedger(krw(0), krw(0)));
        assertThat(empty.hasOffPgRefund()).isFalse();
        assertThat(empty.expectedPgBalance(krw(129_000))).isEqualTo(krw(129_000));

        PaymentCancelLedger pgOnly = PaymentCancelLedger.of(List.of(row(41, 10_000, Status.SUCCEEDED), row(42, 9_000, Status.EXTERNAL_PG_REFUNDED)),
            CurrencyCode.KRW);
        assertThat(pgOnly.pgCanceled()).isEqualTo(pgOnly.refundedTotal());
        assertThat(pgOnly.hasOffPgRefund()).isFalse();
    }

    @Test
    @DisplayName("행의 통화가 결제 통화와 다르면 합계를 내지 않는다")
    void currencyMismatchFails() {
        PaymentCancel usd = new PaymentCancel(41, PAYMENT_ID, Money.of(1_000, CurrencyCode.USD), Status.SUCCEEDED);

        assertThatThrownBy(() -> PaymentCancelLedger.of(List.of(usd), CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PG 취소 합계가 환불 합계를 넘는 원장은 만들 수 없다")
    void pgCanceledNeverExceedsRefunded() {
        assertThatThrownBy(() -> new PaymentCancelLedger(krw(2), krw(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentCancelLedger(null, krw(1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("두 합계의 통화가 다르면 원장을 만들 수 없다 — 금액이 0 이어도 같다")
    void currenciesMustAgree() {
        assertThatThrownBy(() -> new PaymentCancelLedger(krw(0), Money.zero(CurrencyCode.USD)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("통화");
        assertThatThrownBy(() -> new PaymentCancelLedger(Money.of(1, CurrencyCode.USD), krw(5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제액보다 많이 환불된 원장으로 잔액을 물으면 계산 오류로 실패한다 — 음수 잔액을 만들지 않는다")
    void overRefundedBalanceFails() {
        PaymentCancelLedger ledger = new PaymentCancelLedger(krw(10), krw(20));

        assertThatThrownBy(() -> ledger.retained(krw(15))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.expectedPgBalance(krw(15))).isEqualTo(krw(5));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "PENDING, false, false, false, false",
        "SUCCEEDED, true, true, false, false",
        "REJECTED, false, false, false, true",
        "VOIDED, false, false, false, true",
        "EXTERNAL_PG_REFUNDED, true, true, true, false",
        "OFF_PG_REFUNDED, false, true, true, false"})
    @DisplayName("상태별 의미 — PG 잔액 감소 · 환불 확정 · 외부 확인 · 영구 거절")
    void statusMeaning(Status status, boolean reducesPgBalance, boolean countsAsRefunded, boolean refundedExternally, boolean refusesRefund) {
        assertThat(status.reducesPgBalance()).isEqualTo(reducesPgBalance);
        assertThat(status.countsAsRefunded()).isEqualTo(countsAsRefunded);
        assertThat(status.refundedExternally()).isEqualTo(refundedExternally);
        assertThat(status.refusesRefund()).isEqualTo(refusesRefund);
    }

    @Test
    @DisplayName("상태는 위 표의 여섯 개뿐이다 — 새 상태를 더하면 표와 DDL check 를 함께 고친다")
    void statusSetIsClosed() {
        assertThat(Status.values()).extracting(Enum::name)
            .containsExactly("PENDING", "SUCCEEDED", "REJECTED", "VOIDED", "EXTERNAL_PG_REFUNDED", "OFF_PG_REFUNDED");
    }

    @ParameterizedTest(name = "{0} 관측 잔액 {1} → {2}")
    @CsvSource({
        // 결제 129,000 · pgCanceled 29,000(SUCCEEDED 20,000 + EXTERNAL_PG 9,000) · OFF_PG 5,000 → 기대 잔액 100,000, 신고 금액 10,000
        "EXTERNAL_PG_REFUNDED, 90000, EXACT",
        "EXTERNAL_PG_REFUNDED, 89999, SURPLUS",
        "EXTERNAL_PG_REFUNDED, 0, SURPLUS",
        "EXTERNAL_PG_REFUNDED, 90001, NONE",
        "EXTERNAL_PG_REFUNDED, 100000, NONE",
        "EXTERNAL_PG_REFUNDED, 100001, NONE",
        "OFF_PG_REFUNDED, 100000, EXACT",
        "OFF_PG_REFUNDED, 99999, NONE",
        "OFF_PG_REFUNDED, 90000, NONE",
        "OFF_PG_REFUNDED, 100001, NONE"
    })
    @DisplayName("외부 환불 증거 — 원장에 없는 환불액 = (결제액 − pgCanceled) − 관측 잔액. PG 는 = 금액 EXACT · > 금액 SURPLUS, PG 밖은 0 일 때만, 관측 > 기대면 NONE")
    void externalRefundEvidenceBoundaries(Status refunded, long observed, PaymentCancelLedger.Evidence expected) {
        PaymentCancelLedger ledger = PaymentCancelLedger.of(List.of(
            row(41, 20_000, Status.SUCCEEDED),
            row(42, 9_000, Status.EXTERNAL_PG_REFUNDED),
            row(43, 5_000, Status.OFF_PG_REFUNDED)), CurrencyCode.KRW);

        PaymentCancelLedger.Evidence evidence = ledger.externalRefundEvidence(krw(129_000), krw(observed), krw(10_000), refunded);

        assertThat(evidence).isEqualTo(expected);
        assertThat(evidence.recordable()).isEqualTo(expected != PaymentCancelLedger.Evidence.NONE);
    }

    @Test
    @DisplayName("외부 환불 증거는 외부 확인 환불 상태에만 묻는다")
    void externalRefundEvidenceRejectsOtherStatuses() {
        PaymentCancelLedger ledger = PaymentCancelLedger.of(List.of(), CurrencyCode.KRW);

        for (Status status : new Status[] {Status.SUCCEEDED, Status.PENDING, Status.VOIDED, null}) {
            assertThatThrownBy(() -> ledger.externalRefundEvidence(krw(129_000), krw(0), krw(1), status)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
