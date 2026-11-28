package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.util.Collection;
import java.util.Objects;

/**
 * 매출일 하루의 수집 요약 — 한 통화의 거래만 센다. 다른 통화 거래는 저장만 하고 요약 · 판정에서 뺀다(USD 는 #738).
 *
 * @param itemCount 거래 수
 * @param pgAmountTotal 거래 금액 합 — 승인 +, 취소 − 라 음수일 수 있다
 * @param feeTotal 수수료 합
 * @param payOutTotal 지급 금액 합
 * @param currency 요약 통화
 */
public record SettlementCollectionSummary(int itemCount, long pgAmountTotal, long feeTotal, long payOutTotal, CurrencyCode currency) {

    public SettlementCollectionSummary {
        Objects.requireNonNull(currency, "currency");
        if (itemCount < 0) {
            throw new IllegalArgumentException("거래 수는 음수일 수 없습니다");
        }
    }

    /**
     * {@code currency} 거래만 합친다.
     *
     * @throws ArithmeticException 합이 long 범위를 넘을 때 — 조용히 넘치지 않는다
     */
    public static SettlementCollectionSummary of(Collection<PgSettlementItem> items, CurrencyCode currency) {
        int count = 0;
        long amount = 0;
        long fee = 0;
        long payOut = 0;
        for (PgSettlementItem item : items) {
            if (item.currency() != currency) {
                continue;
            }
            count++;
            amount = Math.addExact(amount, item.amount());
            fee = Math.addExact(fee, item.feeAmount());
            payOut = Math.addExact(payOut, item.payOutAmount());
        }
        return new SettlementCollectionSummary(count, amount, fee, payOut, currency);
    }
}
