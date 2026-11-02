package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** 원격 조회·이벤트 대조를 마친 application 값. QueryResult는 Processor 밖에 전달하지 않는다. */
public record VerifiedPayment(long paymentId, long orderId, long memberId, Money amount, Instant approvedAt, boolean done, boolean fullyCanceled) {
}
