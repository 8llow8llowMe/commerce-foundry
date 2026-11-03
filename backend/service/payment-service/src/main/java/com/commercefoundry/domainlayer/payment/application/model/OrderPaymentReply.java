package com.commercefoundry.domainlayer.payment.application.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/**
 * 원천 조회로 확인한 주문 결제 반영 결과 — 회신 이벤트와 맞는 것만 만든다. 로컬 결제와의 대조(회원 · 금액 · 승인 시각)는 기록 트랜잭션이 한다.
 *
 * @param paid 반영 성공이면 true
 * @param rejectionReason 거절 사유 코드. 성공이면 null
 * @param approvedAt 원천의 PG 승인 시각. 거절이면 null 일 수 있다
 */
public record OrderPaymentReply(long paymentId, long orderId, long memberId, Money amount, Instant approvedAt, boolean paid, String rejectionReason) {
}
