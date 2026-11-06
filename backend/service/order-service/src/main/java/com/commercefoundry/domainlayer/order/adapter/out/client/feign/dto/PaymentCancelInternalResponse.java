package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * payment-service 결제 취소 200 응답 (#16 PR-2). 아이디는 문자열, 금액은 최소 단위다. 모르는 칸은 무시한다.
 *
 * @param status 취소 뒤 결제 상태({@code CANCELED} · {@code PARTIAL_CANCELED}) — 분류에 쓰지 않는다
 * @param canceledAmount 이번 클레임의 취소액 — 요청 금액과 같아야 한다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentCancelInternalResponse(String paymentId, String claimId, String status, Long canceledAmount) {
}
