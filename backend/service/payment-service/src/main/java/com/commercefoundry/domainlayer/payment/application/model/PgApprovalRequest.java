package com.commercefoundry.domainlayer.payment.application.model;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * PG 승인 요청 ({@code PaymentGatewayPort#approve}). IN_PROGRESS 로 저장한 결제 시도에서 만든다 — {@link #of(Payment)}.
 *
 * @param pgOrderId PG 주문번호 — 결제 시도 아이디 문자열
 * @param paymentKey successUrl 로 돌아온 PG 결제키
 * @param amount 결제 시도의 금액 — 서버가 정한 주문 금액이다. successUrl 의 금액이 아니다 ({@code payment-guide.md} §3-1)
 * @param idempotencyKey PG 멱등 키. 결제 시도 아이디 문자열이라 같은 시도의 재요청(되돌린 READY 의 재승인 포함)이 같은 값을 싣는다 ({@code payment-guide.md} §3-2)
 */
public record PgApprovalRequest(String pgOrderId, String paymentKey, Money amount, String idempotencyKey) {

    /** 토스 Idempotency-Key 상한. */
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 300;

    public PgApprovalRequest {
        Objects.requireNonNull(pgOrderId, "pgOrderId");
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("멱등 키는 1~%d자여야 합니다".formatted(IDEMPOTENCY_KEY_MAX_LENGTH));
        }
    }

    /**
     * 승인 요청 중(IN_PROGRESS)인 결제 시도의 승인 요청. 멱등 키는 결제 시도 아이디 문자열(= PG 주문번호)이다.
     *
     * @throws IllegalArgumentException 결제키가 없을 때 — IN_PROGRESS 가 아닌 결제 시도다
     */
    public static PgApprovalRequest of(Payment payment) {
        Objects.requireNonNull(payment, "payment");
        if (payment.paymentKey() == null) {
            throw new IllegalArgumentException("결제키가 없는 결제 시도는 승인을 요청할 수 없습니다: " + payment.status());
        }
        return new PgApprovalRequest(payment.pgOrderId(), payment.paymentKey(), payment.amount(), Long.toString(payment.id()));
    }

    /** 결제키 전체를 로그에 남기지 않는다 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "PgApprovalRequest[pgOrderId=%s, amount=%s, idempotencyKey=%s]".formatted(pgOrderId, amount, idempotencyKey);
    }
}
