package com.commercefoundry.domainlayer.order.application.model;

import java.time.Instant;

/** 자동 구매 확정 대상의 (배송 완료 시각, 주문 아이디) 키셋 커서. */
public record OrderConfirmationCandidate(long orderId, Instant deliveredAt) {
}
