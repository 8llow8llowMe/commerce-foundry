package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.application.port.out.query.ClaimAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** 어드민 클레임 목록 한 줄 (#17). 재시도 · 실패 · 운영자 결정 칸은 수습 대상을 목록에서 고르는 데 쓴다. */
public record ClaimAdminSummaryInfo(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Long paymentId, String failureCode, int attemptCount,
    Instant nextAttemptAt, String decisionReason, Long decidedByMemberId, Instant decidedAt, Instant requestedAt
) {
    public static ClaimAdminSummaryInfo from(ClaimAdminSummaryQueryResult row) {
        return new ClaimAdminSummaryInfo(row.claimId(), row.orderId(), row.type(), row.status(), row.expectedRefund(), row.paymentId(), row.failureCode(),
            row.attemptCount(), row.nextAttemptAt(), row.decisionReason(), row.decidedByMemberId(), row.decidedAt(), row.requestedAt());
    }
}
