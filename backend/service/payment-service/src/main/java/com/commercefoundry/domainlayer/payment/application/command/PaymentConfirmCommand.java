package com.commercefoundry.domainlayer.payment.application.command;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import java.util.Objects;

/**
 * 결제 승인 — successUrl 로 돌아온 값 그대로다. 금액은 변조될 수 있어 결제 시도의 금액과 대조할 뿐 승인 요청에 쓰지 않는다 ({@code payment-guide.md} §3-1).
 * 금액 · 통화를 {@code Money} 로 바꾸지 않고 원값으로 둔다 — 모르는 통화도 "금액 불일치" 로 거절하게(변환 예외가 500 으로 새지 않게).
 *
 * @param paymentKey PG 결제키 (1~200자). 로그 · {@link #toString()} 에 앞 8자만 남긴다
 * @param pgOrderId PG 주문번호 — 결제 준비가 내려 준 결제 시도 번호
 * @param amount successUrl 의 금액 (최소 화폐 단위)
 * @param currency successUrl 의 통화 코드
 */
public record PaymentConfirmCommand(String paymentKey, String pgOrderId, long amount, String currency) {

    public PaymentConfirmCommand {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(pgOrderId, "pgOrderId");
        Objects.requireNonNull(currency, "currency");
    }

    /** 결제키 전체를 로그에 남기지 않는다 ({@code coding-conventions.md} §8) — 레코드 기본 {@code toString} 은 원문을 찍는다. */
    @Override
    public String toString() {
        return "PaymentConfirmCommand[paymentKey=%s, pgOrderId=%s, amount=%d, currency=%s]"
            .formatted(Payment.maskPaymentKey(paymentKey), pgOrderId, amount, currency);
    }
}
