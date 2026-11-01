package com.commercefoundry.domainlayer.payment.adapter.out.client.feign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * order-service {@code GET /internal/v1/orders/{orderId}/payment-summary} 응답 본문 중 payment-service 가 읽는 칸. 모르는 칸은 무시한다.
 *
 * <p>모든 칸을 Wrapper · 느슨한 모양으로 받는다 — 빠진 칸이 0 · 기본값으로 읽히거나 역직렬화 단계에서 터지지 않고, 응답 변환
 * ({@code OrderInternalResponseSupport})에서 계약 위반(503)으로 가려지게.
 *
 * @param orderId 주문 아이디 (문자열 — 응답 식별자 규칙)
 * @param memberId 주문한 회원 아이디 (문자열)
 * @param status 주문 상태 이름 — 결제 대기는 {@code PENDING_PAYMENT}
 * @param totalAmount 주문 총액
 * @param orderName 결제창 주문명 (100자 이하)
 * @param paymentDueAt 결제 기한 (UTC)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaymentSummaryInternalResponse(
    String orderId, String memberId, String status, Amount totalAmount, String orderName, Instant paymentDueAt
) {

    /** 금액 — 최소 화폐 단위 정수 + 통화 코드. 공용 {@code MoneyItem} 은 통화가 없으면 생성자에서 터져 계약 위반으로 가를 수 없어 따로 둔다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(Long amount, String currency) {
    }
}
