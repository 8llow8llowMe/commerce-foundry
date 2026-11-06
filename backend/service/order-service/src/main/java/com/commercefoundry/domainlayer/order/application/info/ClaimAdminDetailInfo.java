package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;

/**
 * 어드민 접수 상세. 조회에 불필요한 버전과 멱등 키를 전달하지 않는다. 재시도 · 실패 · 운영자 결정 칸은 수습 화면(#17 PR-A)이 쓴다.
 *
 * @param paymentId 환불할 결제 아이디. 반품이면 null
 * @param decisionReason 운영자 결정 사유. 결정이 없으면 null (아래 결정 칸도 함께 null)
 */
public record ClaimAdminDetailInfo(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Long paymentId, String failureCode, int attemptCount,
    Instant nextAttemptAt, String decisionReason, Long decidedByMemberId, Instant decidedAt, Instant requestedAt, List<Line> items
) {
    public static ClaimAdminDetailInfo from(Claim claim) {
        Claim.Decision decision = claim.decision();
        return new ClaimAdminDetailInfo(claim.id(), claim.orderId(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            claim.failureCode(), claim.attemptCount(), claim.nextAttemptAt(), decision == null ? null : decision.reason(),
            decision == null ? null : decision.decidedByMemberId(), decision == null ? null : decision.decidedAt(), claim.requestedAt(),
            claim.items().stream().map(item -> new Line(item.orderItemId(), item.quantity(), item.reason())).toList());
    }

    public record Line(long orderItemId, int quantity, ClaimItem.Reason reason) {
    }
}
