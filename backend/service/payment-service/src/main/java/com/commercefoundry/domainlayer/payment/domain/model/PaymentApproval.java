package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * PG 가 승인했다고 답한 결과 — 승인 응답이나 결제 조회 응답에서 PG 어댑터가 만든다. {@link Payment#approve} · {@link Payment#resolve} 가 받는다.
 *
 * <p>여기서는 모양만 본다. 결제키 · 금액이 결제 시도와 맞는지는 {@link Payment} 가 전이할 때 본다.
 *
 * @param paymentKey PG 결제키
 * @param easyPayProvider 간편결제 제공자 표시명 (예: 카카오페이). 간편결제가 아니면 null
 * @param approvedAt PG 가 알려 준 승인 시각
 * @param pgStatus PG 원본 상태 문자열 (예: {@code DONE}) — 대사 · 장애 분석용
 * @param amount PG 가 승인한 금액 (최소 화폐 단위)
 */
public record PaymentApproval(String paymentKey, PaymentMethod method, String easyPayProvider, Instant approvedAt, String pgStatus, Money amount) {

    public PaymentApproval {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(approvedAt, "approvedAt");
        Objects.requireNonNull(amount, "amount");
    }

    /** 결제키 전체를 로그에 남기지 않는다 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "PaymentApproval[paymentKey=%s, method=%s, easyPayProvider=%s, approvedAt=%s, pgStatus=%s, amount=%s]"
            .formatted(Payment.maskPaymentKey(paymentKey), method, easyPayProvider, approvedAt, pgStatus, amount);
    }
}
