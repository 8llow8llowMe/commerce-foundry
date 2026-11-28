package com.commercefoundry.domainlayer.settlement.application.info;

import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDayStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.LocalDate;

/**
 * 매출일 하루의 수집 · 대사 결과.
 *
 * @param soldDate PG 매출일 (KST)
 * @param itemCount 수집한 거래 수 ({@code currency} 거래만)
 * @param pgAmountTotal 거래 금액 합 — 승인 +, 취소 − (음수일 수 있다)
 * @param currency 통화
 * @param mismatchCount 이번 대사가 본 결제들의 미해결 불일치 수
 * @param newMismatchCount 이번 대사에서 새로 열리거나 다시 열린 불일치 수
 * @param autoClearedCount 이번 대사에서 자동으로 닫은 불일치 수
 * @param graceDeferredCount PG 누락 유예 중이라 기록하지 않은 결제 수
 * @param status 매출일 정산 상태
 */
public record SettlementReconcileInfo(
    LocalDate soldDate, int itemCount, long pgAmountTotal, CurrencyCode currency, int mismatchCount, int newMismatchCount, int autoClearedCount,
    int graceDeferredCount, SettlementDayStatus status
) {
}
