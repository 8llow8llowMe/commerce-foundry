package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * payment-service 무환불 판정 200 응답 (#17 A4, {@code payment-guide.md} §8-1). 판정은 오류가 아니라 이 데이터다. 아이디는 문자열이다.
 * 분류에 쓰지 않는 칸({@code paymentStatus} · {@code retainedAmount})과 모르는 칸은 무시한다.
 *
 * @param outcome 판정 — {@code NO_REFUND_CONFIRMED} · {@code REFUNDED} · {@code UNCONFIRMED}. 정확히 같은 문자열만 인정한다
 * @param reason 판정 이유 코드 — 로그와 {@code PG_UNAVAILABLE} 분류에만 쓴다
 * @param sealed 이 클레임이 payment 에서 영구 거부되는 상태인가 — {@code NO_REFUND_CONFIRMED} 이면 true 여야 한다. 칸이 없으면 null(확정으로 보지 않는다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentCancelVoidInternalResponse(String paymentId, String claimId, String outcome, String reason, Boolean sealed) {
}
