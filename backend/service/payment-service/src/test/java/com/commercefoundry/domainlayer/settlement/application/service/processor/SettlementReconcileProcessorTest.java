package com.commercefoundry.domainlayer.settlement.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancelLedger;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentLedgerEntry;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.info.SettlementReconcileInfo;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementDayRepositoryPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementItemRepositoryPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementMismatchRepositoryPort;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementCollectionSummary;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDayStatus;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatch;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchResolution;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchStatus;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementPaymentMethod;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 대사 Processor — 저장 포트는 메모리 가짜다. 결제키 · 거래키는 형식만 흉내 낸 합성 값이다. */
class SettlementReconcileProcessorTest {

    static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");
    /** 2026-11-20 10:00 KST. */
    static final Instant APPROVED_AT = Instant.parse("2026-11-20T01:00:00Z");
    static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    static final LocalDate TODAY = SOLD_DATE.plusDays(5);
    static final String APPROVAL_KEY = "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11";

    FakeDays days;
    FakeItems items;
    FakeMismatches mismatches;
    SettlementReconcileProcessor processor;

    @BeforeEach
    void setUp() {
        days = new FakeDays();
        items = new FakeItems();
        mismatches = new FakeMismatches();
        processor = new SettlementReconcileProcessor(days, items, mismatches, new SnowflakeIdGenerator(1, 1));
    }

    @Test
    @DisplayName("결제키마다 판정해 불일치를 열고 매출일을 RECONCILED 로 둔다 — 응답 수치는 이번 대사 결과다")
    void opensMismatchesAndReconcilesDay() {
        collect(approval("tgen_ok_0001", "tx-1", 129_000), approval("tgen_amount_0002", "tx-2", 100_000), approval("tgen_orphan_0003", "tx-3", 5_000));

        SettlementReconcileInfo info = run(List.of(krw(9_000_000_001L, "tgen_ok_0001", 129_000, 0), krw(9_000_000_002L, "tgen_amount_0002", 129_000, 0)), TODAY);

        assertThat(info.status()).isEqualTo(SettlementDayStatus.RECONCILED);
        assertThat(info.itemCount()).isEqualTo(3);
        assertThat(info.pgAmountTotal()).isEqualTo(234_000);
        assertThat(info.mismatchCount()).isEqualTo(2);
        assertThat(info.newMismatchCount()).isEqualTo(2);
        assertThat(info.autoClearedCount()).isZero();
        assertThat(mismatches.open()).extracting(SettlementMismatch::type)
            .containsExactlyInAnyOrder(SettlementMismatchType.AMOUNT, SettlementMismatchType.MISSING_IN_LEDGER);
        assertThat(days.rows.get(SOLD_DATE).status()).isEqualTo(SettlementDayStatus.RECONCILED);
        assertThat(days.rows.get(SOLD_DATE).mismatchCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("USD 결제 · 거래는 대사 대상에서 뺀다 (#738)")
    void excludesUsd() {
        PgSettlementItem usdItem = new PgSettlementItem("tx-usd", "tgen_usd_0001", null, SOLD_DATE, null, SettlementPaymentMethod.CARD, 8_950, 0, 0,
            CurrencyCode.USD, false, APPROVED_AT);
        collect(usdItem, approval("tgen_mixed_0002", "tx-2", 100_000));

        SettlementReconcileInfo info = run(List.of(usd(9_000_000_009L, "tgen_usd_0001", 8_950), usd(9_000_000_010L, "tgen_mixed_0002", 100_000)), TODAY);

        assertThat(mismatches.rows).isEmpty();
        assertThat(info.mismatchCount()).isZero();
        assertThat(info.itemCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 매출일을 다시 돌려도 결과가 같다 — 행이 늘지 않고 새로 연 수는 0")
    void rerunIsIdempotent() {
        collect(approval("tgen_amount_0002", "tx-2", 100_000));
        List<PaymentLedgerEntry> ledger = List.of(krw(9_000_000_002L, "tgen_amount_0002", 129_000, 0));
        run(ledger, TODAY);
        Set<Long> firstIds = Set.copyOf(mismatches.rows.keySet());

        recollect(approval("tgen_amount_0002", "tx-2", 100_000));
        SettlementReconcileInfo second = run(ledger, TODAY);

        assertThat(mismatches.rows.keySet()).isEqualTo(firstIds);
        assertThat(second.mismatchCount()).isEqualTo(1);
        assertThat(second.newMismatchCount()).isZero();
        assertThat(second.autoClearedCount()).isZero();
    }

    @Test
    @DisplayName("더는 어긋나지 않으면 AUTO_CLEARED 로 닫고, 다시 어긋나면 같은 행을 OPEN 으로 되돌린다")
    void autoClearsAndReopens() {
        List<PaymentLedgerEntry> ledger = List.of(krw(9_000_000_002L, "tgen_amount_0002", 129_000, 0));
        collect(approval("tgen_amount_0002", "tx-2", 100_000));
        run(ledger, TODAY);
        long id = mismatches.rows.keySet().iterator().next();

        recollect(approval("tgen_amount_0002", "tx-2", 129_000));
        SettlementReconcileInfo cleared = run(ledger, TODAY);

        SettlementMismatch closed = mismatches.rows.get(id);
        assertThat(cleared.autoClearedCount()).isEqualTo(1);
        assertThat(cleared.mismatchCount()).isZero();
        assertThat(closed.status()).isEqualTo(SettlementMismatchStatus.RESOLVED);
        assertThat(closed.resolution()).isEqualTo(SettlementMismatchResolution.AUTO_CLEARED);

        recollect(approval("tgen_amount_0002", "tx-2", 90_000));
        SettlementReconcileInfo reopened = run(ledger, TODAY);

        assertThat(mismatches.rows).hasSize(1);
        assertThat(mismatches.rows.get(id).status()).isEqualTo(SettlementMismatchStatus.OPEN);
        assertThat(mismatches.rows.get(id).actualAmount()).isEqualTo(90_000);
        assertThat(reopened.newMismatchCount()).isEqualTo(1);
        assertThat(reopened.mismatchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("부분 취소가 다른 날 수집돼도 결제키 누계로 맞으면 불일치가 없다")
    void partialCancelOnOtherDayMatches() {
        items.upsertAll(List.of(cancel("tgen_partial_0004", "tx-c1", 29_000, SOLD_DATE.plusDays(1))), NOW);
        collect(approval("tgen_partial_0004", "tx-a1", 129_000));

        SettlementReconcileInfo info = run(List.of(krw(9_000_000_004L, "tgen_partial_0004", 129_000, 29_000)), TODAY);

        assertThat(mismatches.rows).isEmpty();
        assertThat(info.mismatchCount()).isZero();
    }

    @Test
    @DisplayName("PG 누락 — 매출일 + 2일엔 유예로 세기만 하고, + 3일엔 기록한다")
    void missingInPgGrace() {
        collect();
        List<PaymentLedgerEntry> ledger = List.of(krw(9_000_000_005L, "tgen_missing_0005", 129_000, 0));

        SettlementReconcileInfo deferred = run(ledger, SOLD_DATE.plusDays(2));
        assertThat(deferred.graceDeferredCount()).isEqualTo(1);
        assertThat(mismatches.rows).isEmpty();

        recollect();
        SettlementReconcileInfo recorded = run(ledger, SOLD_DATE.plusDays(3));
        assertThat(recorded.graceDeferredCount()).isZero();
        assertThat(mismatches.open()).extracting(SettlementMismatch::type).containsExactly(SettlementMismatchType.MISSING_IN_PG);
    }

    @Test
    @DisplayName("매출일이 없거나 COLLECTED 가 아니면 409 SETTLEMENT_002 — 이 실행의 수집 결과가 아니다")
    void prepareRequiresCollectedDay() {
        assertThatThrownBy(() -> processor.prepare(SOLD_DATE)).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.RECONCILE_CONFLICT));

        days.save(SettlementDay.failed(1L, SOLD_DATE, CurrencyCode.KRW));
        assertThatThrownBy(() -> processor.prepare(SOLD_DATE)).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.RECONCILE_CONFLICT));
    }

    @Test
    @DisplayName("대상은 매출일 거래의 결제키 ∪ 원장 승인 범위다 — 범위는 KST 하루")
    void prepareReturnsKstRange() {
        collect(approval("tgen_ok_0001", "tx-1", 129_000));

        var target = processor.prepare(SOLD_DATE);

        assertThat(target.paymentKeys()).containsExactly("tgen_ok_0001");
        assertThat(target.approvedFrom()).isEqualTo(Instant.parse("2026-11-19T15:00:00Z"));
        assertThat(target.approvedTo()).isEqualTo(Instant.parse("2026-11-20T15:00:00Z"));
    }

    private SettlementReconcileInfo run(List<PaymentLedgerEntry> ledger, LocalDate today) {
        return processor.reconcile(processor.prepare(SOLD_DATE), ledger, today, NOW);
    }

    private void collect(PgSettlementItem... collected) {
        items.upsertAll(List.of(collected), NOW);
        days.save(SettlementDay.collected(1L, SOLD_DATE, SettlementCollectionSummary.of(List.of(collected), CurrencyCode.KRW), NOW));
    }

    private void recollect(PgSettlementItem... collected) {
        items.upsertAll(List.of(collected), NOW);
        days.save(days.rows.get(SOLD_DATE).recollect(SettlementCollectionSummary.of(List.of(collected), CurrencyCode.KRW), NOW));
    }

    static PgSettlementItem approval(String paymentKey, String transactionKey, long amount) {
        return new PgSettlementItem(transactionKey, paymentKey, null, SOLD_DATE, null, SettlementPaymentMethod.CARD, amount, 0, 0, CurrencyCode.KRW, false,
            APPROVED_AT);
    }

    static PgSettlementItem cancel(String paymentKey, String transactionKey, long amount, LocalDate soldDate) {
        return new PgSettlementItem(transactionKey, paymentKey, null, soldDate, null, SettlementPaymentMethod.CARD, -amount, 0, 0, CurrencyCode.KRW, true,
            APPROVED_AT);
    }

    static PaymentLedgerEntry krw(long id, String paymentKey, long amount, long pgCanceled) {
        Money paid = Money.of(amount, CurrencyCode.KRW);
        Payment done = Payment.ready(id, id + 1, 7_000_000_001L, paid, "생활한복 저고리")
            .startApproval(paymentKey, APPROVAL_KEY, APPROVED_AT.minusSeconds(3))
            .approve(new PaymentApproval(paymentKey, PaymentMethod.CARD, null, APPROVED_AT, "DONE", paid));
        Payment payment = pgCanceled == 0 ? done : done.recordCancellation(Money.of(pgCanceled, CurrencyCode.KRW));
        Money canceled = Money.of(pgCanceled, CurrencyCode.KRW);
        return new PaymentLedgerEntry(payment, new PaymentCancelLedger(canceled, canceled));
    }

    static PaymentLedgerEntry usd(long id, String paymentKey, long amount) {
        Money paid = Money.of(amount, CurrencyCode.USD);
        Payment payment = new Payment(id, id + 1, 7_000_000_001L, Long.toString(id), PaymentGateway.TOSS, paid, "Hanbok jeogori", PaymentStatus.DONE,
            paymentKey, PaymentMethod.CARD, null, "DONE", APPROVED_AT.minusSeconds(3), APPROVED_AT, null, null, null, false, null, null, APPROVAL_KEY, 1L);
        Money zero = Money.zero(CurrencyCode.USD);
        return new PaymentLedgerEntry(payment, new PaymentCancelLedger(zero, zero));
    }

    static final class FakeDays implements SettlementDayRepositoryPort {

        final Map<LocalDate, SettlementDay> rows = new LinkedHashMap<>();

        @Override
        public Optional<SettlementDay> lockBySoldDate(LocalDate soldDate) {
            return Optional.ofNullable(rows.get(soldDate));
        }

        @Override
        public SettlementDay save(SettlementDay day) {
            SettlementDay saved = new SettlementDay(day.id(), day.soldDate(), day.status(), day.itemCount(), day.pgAmountTotal(), day.feeTotal(),
                day.payOutTotal(), day.currency(), day.mismatchCount(), day.collectedAt(), day.reconciledAt(), day.version() == null ? 0L : day.version() + 1);
            rows.put(day.soldDate(), saved);
            return saved;
        }
    }

    static final class FakeItems implements SettlementItemRepositoryPort {

        final Map<String, PgSettlementItem> rows = new LinkedHashMap<>();

        @Override
        public void upsertAll(Collection<PgSettlementItem> collected, Instant collectedAt) {
            collected.forEach(item -> rows.put(item.transactionKey(), item));
        }

        @Override
        public Set<String> findPaymentKeysBySoldDate(LocalDate soldDate, CurrencyCode currency) {
            return rows.values().stream().filter(item -> item.soldDate().equals(soldDate) && item.currency() == currency)
                .map(PgSettlementItem::paymentKey).collect(Collectors.toSet());
        }

        @Override
        public List<PgSettlementItem> findByPaymentKeys(Collection<String> paymentKeys) {
            return rows.values().stream().filter(item -> paymentKeys.contains(item.paymentKey())).toList();
        }
    }

    static final class FakeMismatches implements SettlementMismatchRepositoryPort {

        final Map<Long, SettlementMismatch> rows = new LinkedHashMap<>();

        List<SettlementMismatch> open() {
            return rows.values().stream().filter(SettlementMismatch::isOpen).toList();
        }

        @Override
        public List<SettlementMismatch> findByPaymentKeys(Collection<String> paymentKeys) {
            return rows.values().stream().filter(mismatch -> paymentKeys.contains(mismatch.paymentKey())).toList();
        }

        @Override
        public List<SettlementMismatch> saveAll(Collection<SettlementMismatch> changed) {
            Set<String> uniqueKeys = new HashSet<>();
            List<SettlementMismatch> saved = new ArrayList<>();
            for (SettlementMismatch mismatch : changed) {
                assertThat(uniqueKeys.add(mismatch.paymentKey() + "|" + mismatch.type())).as("한 번의 저장에 같은 (결제키, 종류)가 둘").isTrue();
                SettlementMismatch stored = new SettlementMismatch(mismatch.id(), mismatch.soldDate(), mismatch.paymentKey(), mismatch.paymentId(),
                    mismatch.type(), mismatch.status(), mismatch.resolution(), mismatch.expectedAmount(), mismatch.actualAmount(), mismatch.currency(),
                    mismatch.detectedAt(), mismatch.resolvedAt(), mismatch.version() == null ? 0L : mismatch.version() + 1);
                rows.put(stored.id(), stored);
                saved.add(stored);
            }
            return saved;
        }
    }
}
