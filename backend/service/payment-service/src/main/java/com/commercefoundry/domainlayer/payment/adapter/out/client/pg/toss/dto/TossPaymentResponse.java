package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * 토스 Payment 객체 중 승인 · 조회 결과를 가르는 데 쓰는 칸. 나머지 칸(카드 · 영수증 · 취소 이력 등)은 읽지 않는다.
 * 모든 칸이 빠질 수 있다고 보고 {@code TossPaymentsResponseSupport} 가 필수 칸을 확인한다.
 *
 * @param method 결제수단 표시명 (예: {@code 카드} · {@code 간편결제}). 승인 전이면 null
 * @param status 결제 상태 (READY · IN_PROGRESS · WAITING_FOR_DEPOSIT · DONE · CANCELED · PARTIAL_CANCELED · ABORTED · EXPIRED)
 * @param approvedAt ISO-8601 오프셋 시각 (예: {@code 2026-10-21T10:00:03+09:00}). 승인 전이면 null
 * @param totalAmount 통화의 주 단위 총 결제 금액
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentResponse(
    String paymentKey, String orderId, String currency, String method, String status, String approvedAt, BigDecimal totalAmount,
    EasyPay easyPay, Failure failure
) {

    /** 결제키 전체 · 실패 메시지를 로그에 남기지 않는다 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "TossPaymentResponse[orderId=%s, status=%s, method=%s, totalAmount=%s, currency=%s]".formatted(orderId, status, method, totalAmount, currency);
    }

    /** 간편결제 정보. {@code provider} 는 표시명이다 (예: {@code 카카오페이}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EasyPay(String provider) {
    }

    /** 결제 실패 정보 (ABORTED · EXPIRED 등). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String code, String message) {

        @Override
        public String toString() {
            return "Failure[code=" + code + "]";
        }
    }
}
