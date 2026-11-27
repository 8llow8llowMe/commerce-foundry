package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 어드민 반품 승인 (#17 B2c-2). 금액은 계산에 쓰지 않는다 — 서버가 미리보기와 같은 판정 · 산식으로 다시 계산하고 {@code expectedRefund} 는 대조에만 쓴다.
 *
 * @param expectedVersion 운영자가 본 상세의 {@code recovery.version}
 * @param fault 운영자가 고른 귀책. null 이면 항목 사유로 정한 기본 귀책({@link ReturnRefundPolicy.Fault#defaultOf})
 * @param restock 검수 뒤 재고로 되돌릴지
 * @param deduction 정책 금액에서 뺄 금액(반품 배송비 · 훼손 등, 0 이상)
 * @param expectedRefund 운영자가 화면에서 본 환불액(정책 금액 − 차감액, 양수)
 */
public record ClaimReturnApprovalCommand(
    long claimId, long expectedVersion, ReturnRefundPolicy.Fault fault, boolean restock, Money deduction, Money expectedRefund
) {
    public ClaimReturnApprovalCommand {
        Objects.requireNonNull(deduction, "deduction");
        Objects.requireNonNull(expectedRefund, "expectedRefund");
    }
}
