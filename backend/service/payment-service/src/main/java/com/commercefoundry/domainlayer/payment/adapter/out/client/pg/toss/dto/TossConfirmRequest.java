package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto;

import java.math.BigDecimal;

/**
 * 토스 결제 승인 요청 본문 ({@code POST /v1/payments/confirm}).
 *
 * @param amount 통화의 주 단위 금액 — KRW {@code 129000}, USD {@code 89.50}. {@code Money#toMajorUnit} 으로 만든다
 */
public record TossConfirmRequest(String paymentKey, String orderId, BigDecimal amount) {

    /** 결제키 전체를 로그 · 예외 메시지에 남기지 않는다 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "TossConfirmRequest[orderId=" + orderId + ", amount=" + amount + "]";
    }
}
