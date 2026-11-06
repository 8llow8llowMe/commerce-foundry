package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** 어드민 클레임 조회 (#17). 재시도 · 실패 · 운영자 결정 칸은 수습(#17 PR-A) 화면용이다. */
public record ClaimAdminSummaryQueryResult(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Long paymentId, String failureCode, int attemptCount,
    Instant nextAttemptAt, String decisionReason, Long decidedByMemberId, Instant decidedAt, Instant requestedAt
) {
    /** QueryDSL 프로젝션 — 금액은 금액 · 통화 두 열로 받는다. */
    public ClaimAdminSummaryQueryResult(long claimId, long orderId, Claim.Type type, Claim.Status status,
        Long expectedRefundAmount, CurrencyCode expectedRefundCurrency, Long paymentId, String failureCode, int attemptCount, Instant nextAttemptAt,
        String decisionReason, Long decidedByMemberId, Instant decidedAt, Instant requestedAt) {
        this(claimId, orderId, type, status, MoneyColumns.nullable(expectedRefundAmount, expectedRefundCurrency), paymentId, failureCode, attemptCount,
            nextAttemptAt, decisionReason, decidedByMemberId, decidedAt, requestedAt);
    }
}
