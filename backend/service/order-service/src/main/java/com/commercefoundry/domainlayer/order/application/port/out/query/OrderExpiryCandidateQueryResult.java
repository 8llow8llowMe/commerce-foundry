package com.commercefoundry.domainlayer.order.application.port.out.query;

import java.time.Instant;

/**
 * 미결제 만료 대상 한 건 — 주문 아이디 · 결제 기한만 투영한 조회 결과({@code OrderRepository} JPQL 생성자 투영)다.
 * Processor 가 Facade 용 커서({@code OrderExpiryCandidate})로 바꿔 돌려준다.
 */
public record OrderExpiryCandidateQueryResult(long orderId, Instant paymentDueAt) {
}
