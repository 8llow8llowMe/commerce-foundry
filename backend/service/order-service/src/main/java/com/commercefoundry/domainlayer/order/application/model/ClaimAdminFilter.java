package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import java.util.List;

/** 어드민 클레임 조회 (#17). */
public record ClaimAdminFilter(Long orderId, Type type, Status status, int page, int size) {
    public ClaimAdminFilter {
        if ((long) page * size > Integer.MAX_VALUE) {
            String[] message = OrderValidationMessage.CLAIM_PAGE_OFFSET_RANGE.split(":", 2);
            throw new OrderFieldValidationException(List.of(new OrderFieldValidationException.Violation(message[0], "page", message[1])));
        }
    }

    public enum Type {
        RETURN, CANCELLATION;
        public Claim.Type toDomain() { return Claim.Type.valueOf(name()); }
    }

    public enum Status {
        REQUESTED, APPROVED, REFUNDED, COMPLETED, REJECTED, REFUND_FAILED, RESTORATION_FAILED;
        public Claim.Status toDomain() { return Claim.Status.valueOf(name()); }
    }
}
