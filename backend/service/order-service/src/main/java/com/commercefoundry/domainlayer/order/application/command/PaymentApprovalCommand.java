package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.UUID;

/** 승인 알림. 원본 진위는 PaymentApprovalProcessor가 재조회해 확인한다. */
public record PaymentApprovalCommand(UUID eventId, long paymentId, long orderId, long memberId, Money amount, Instant approvedAt, String method) {
}
