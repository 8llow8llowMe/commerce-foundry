package com.commercefoundry.domainlayer.payment.domain.model;

/**
 * 결제 시도를 보낸 PG. 국내 카드 · 간편결제와 해외 PayPal 모두 토스페이먼츠 통합 계약으로 받는다 ({@code payment-guide.md} §1).
 * 다른 PG 를 붙이면 값을 더하고 {@code payment.gateway} check 제약도 drop / add 한다 ({@code coding-conventions.md} §9-8).
 */
public enum PaymentGateway {

    TOSS
}
