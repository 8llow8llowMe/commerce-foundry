package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** 어드민 클레임 조회 (#17). */
public record ClaimAdminSummaryQueryResult(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Instant requestedAt
) {
    public ClaimAdminSummaryQueryResult(long claimId, long orderId, Claim.Type type, Claim.Status status,
        Long expectedRefundAmount, CurrencyCode expectedRefundCurrency, Instant requestedAt) {
        this(claimId, orderId, type, status, MoneyColumns.nullable(expectedRefundAmount, expectedRefundCurrency), requestedAt);
    }
}
