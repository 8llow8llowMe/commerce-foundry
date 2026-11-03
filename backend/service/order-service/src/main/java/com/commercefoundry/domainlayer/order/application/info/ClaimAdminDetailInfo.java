package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;

/** 어드민 접수 상세. 조회에 불필요한 버전과 멱등 키를 전달하지 않는다. */
public record ClaimAdminDetailInfo(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Instant requestedAt, List<Line> items
) {
    public static ClaimAdminDetailInfo from(Claim claim) {
        return new ClaimAdminDetailInfo(claim.id(), claim.orderId(), claim.type(), claim.status(), claim.expectedRefund(), claim.requestedAt(),
            claim.items().stream().map(item -> new Line(item.orderItemId(), item.quantity(), item.reason())).toList());
    }

    public record Line(long orderItemId, int quantity, ClaimItem.Reason reason) {
    }
}
