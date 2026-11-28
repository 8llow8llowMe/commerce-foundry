package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 정산 대사 판정 규칙 (#219, {@code payment-guide.md} §10). 결제키 단위 누계로 맞춘다 — 거래 건별로 짝짓지 않는다(우리 취소 원장에 PG 거래키가 없다).
 * 결제키의 <b>수집된 모든</b> PG 거래(매출일 무관)를 본다. 금액은 모두 같은 통화(KRW)다 — 다른 통화는 호출자가 미리 뺀다.
 *
 * <ul>
 *   <li>PG 승인합 = Σ 승인 거래 금액, PG 취소합 = Σ |취소 거래 금액|, PG 순액 = 승인합 − 취소합</li>
 *   <li>우리 기록 = 결제 금액 · PG 취소 누계(payment 취소 원장의 {@code pgCanceled})</li>
 * </ul>
 *
 * <table>
 *   <caption>판정 (한 결제에 여러 개 나올 수 있다)</caption>
 *   <tr><th>종류</th><th>조건</th><th>기대(우리)</th><th>실제(PG)</th></tr>
 *   <tr><td>MISSING_IN_LEDGER</td><td>결제 기록에 결제키가 없다</td><td>0</td><td>PG 순액</td></tr>
 *   <tr><td>STATUS</td><td>PG 승인 거래가 있는데 결제 기록이 결제됨 계열이 아니다</td><td>0</td><td>PG 승인합</td></tr>
 *   <tr><td>AMOUNT</td><td>PG 승인 거래가 있고 승인합 ≠ 결제 금액</td><td>결제 금액</td><td>PG 승인합</td></tr>
 *   <tr><td>CANCEL_TOTAL</td><td>PG 승인 거래가 있고 취소합 ≠ PG 취소 누계</td><td>PG 취소 누계</td><td>PG 취소합</td></tr>
 *   <tr><td>MISSING_IN_PG</td><td>결제됨 계열이고 승인 시각이 매출일(KST)인데 PG 승인 거래가 없다 — 매출일 + 3일이 지나야 기록</td><td>결제 금액</td><td>0</td></tr>
 * </table>
 *
 * <p>금액 · 상태 · 취소 판정(STATUS · AMOUNT · CANCEL_TOTAL)은 PG 승인 거래가 수집된 결제에만 한다. 승인 거래가 없으면 판정할 근거가 없다 —
 * 결제 기록만 있으면 PG 누락만 보고(유예 포함), 취소 거래만 수집됐으면(승인이 수집 범위 밖 날짜) 판정을 미룬다. 그러지 않으면 PG 정산 반영이 늦은 결제마다
 * 유예 없이 AMOUNT 가 쌓인다.
 */
public final class SettlementReconciliation {

    /** 대사 통화 — 지금은 KRW 만 맞춘다. USD 결제 · 거래는 저장만 하고 대사 대상에서 뺀다(#738). */
    public static final CurrencyCode CURRENCY = CurrencyCode.KRW;

    private SettlementReconciliation() {
    }

    /**
     * 결제키 하나를 판정한다.
     *
     * @param items 이 결제키의 수집된 PG 거래 전부 — 다른 결제키 거래가 섞이면 실패한다
     * @param ledger 이 결제키의 결제 기록. 없으면 null
     * @param soldDate 대사 중인 매출일 — PG 누락 판정의 승인일 기준
     * @param today 오늘(KST) — PG 누락 유예 기준
     */
    public static SettlementJudgement judge(
        String paymentKey, Collection<PgSettlementItem> items, SettlementLedgerPayment ledger, LocalDate soldDate, LocalDate today
    ) {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(soldDate, "soldDate");
        Objects.requireNonNull(today, "today");
        long approvedSum = 0;
        long canceledSum = 0;
        boolean hasApproval = false;
        for (PgSettlementItem item : items) {
            if (!item.paymentKey().equals(paymentKey)) {
                throw new IllegalArgumentException("다른 결제키의 거래가 섞였습니다");
            }
            approvedSum = Math.addExact(approvedSum, item.approvedAmount());
            canceledSum = Math.addExact(canceledSum, item.canceledAmount());
            hasApproval |= !item.canceled();
        }
        if (ledger != null && !ledger.paymentKey().equals(paymentKey)) {
            throw new IllegalArgumentException("다른 결제키의 결제 기록입니다");
        }

        EnumSet<SettlementMismatchType> evaluated = EnumSet.noneOf(SettlementMismatchType.class);
        List<SettlementDetection> detections = new ArrayList<>();
        if (ledger == null) {
            if (!items.isEmpty()) {
                evaluated.add(SettlementMismatchType.MISSING_IN_LEDGER);
                detections.add(new SettlementDetection(paymentKey, null, SettlementMismatchType.MISSING_IN_LEDGER, 0, Math.subtractExact(approvedSum, canceledSum)));
            }
            return new SettlementJudgement(paymentKey, detections, evaluated, false);
        }

        evaluated.add(SettlementMismatchType.MISSING_IN_LEDGER);
        long paymentId = ledger.paymentId();
        if (hasApproval) {
            evaluated.addAll(EnumSet.of(SettlementMismatchType.AMOUNT, SettlementMismatchType.STATUS, SettlementMismatchType.MISSING_IN_PG,
                SettlementMismatchType.CANCEL_TOTAL));
            if (approvedSum != ledger.amount()) {
                detections.add(new SettlementDetection(paymentKey, paymentId, SettlementMismatchType.AMOUNT, ledger.amount(), approvedSum));
            }
            if (!ledger.approved()) {
                detections.add(new SettlementDetection(paymentKey, paymentId, SettlementMismatchType.STATUS, 0, approvedSum));
            }
            if (canceledSum != ledger.pgCanceled()) {
                detections.add(new SettlementDetection(paymentKey, paymentId, SettlementMismatchType.CANCEL_TOTAL, ledger.pgCanceled(), canceledSum));
            }
            return new SettlementJudgement(paymentKey, detections, evaluated, false);
        }

        boolean graceDeferred = false;
        if (ledger.approved() && SettlementCalendar.within(soldDate, ledger.approvedAt())) {
            if (SettlementCalendar.graceElapsed(soldDate, today)) {
                evaluated.add(SettlementMismatchType.MISSING_IN_PG);
                detections.add(new SettlementDetection(paymentKey, paymentId, SettlementMismatchType.MISSING_IN_PG, ledger.amount(), 0));
            } else {
                graceDeferred = true;
            }
        }
        return new SettlementJudgement(paymentKey, detections, evaluated, graceDeferred);
    }
}
