package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.application.port.out.query.ClaimAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

public record ClaimAdminSummaryInfo(long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Instant requestedAt) {
    public static ClaimAdminSummaryInfo from(ClaimAdminSummaryQueryResult row) {
        return new ClaimAdminSummaryInfo(row.claimId(), row.orderId(), row.type(), row.status(), row.expectedRefund(), row.requestedAt());
    }
}
