package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 정산 대사 불일치 종류 ({@code payment-guide.md} §10). 한 결제에 여러 종류가 함께 나올 수 있다. 금액 칸(기대 · 실제)의 뜻은 종류마다 다르다 — {@link SettlementReconciliation}.
 *
 * <p>값을 더하면 {@code settlement_mismatch.type} 의 check 제약을 drop / add 한다 ({@code coding-conventions.md} §9-8).
 */
@Getter
@RequiredArgsConstructor
public enum SettlementMismatchType implements CodeNameDescribable {

    AMOUNT("승인 금액 불일치", "PG 승인 금액 합계가 결제 금액과 다릅니다."),
    STATUS("상태 불일치", "PG 는 승인했는데 결제 기록이 승인 상태가 아닙니다."),
    MISSING_IN_PG("PG 누락", "결제 기록은 승인인데 PG 정산 내역에 승인 거래가 없습니다."),
    MISSING_IN_LEDGER("결제 기록 누락", "PG 정산 내역에 있는 결제키가 결제 기록에 없습니다."),
    CANCEL_TOTAL("취소 금액 불일치", "PG 취소 금액 합계가 결제 기록의 PG 취소 합계와 다릅니다.");

    private final String displayName;
    private final String description;
}
