package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.shared.commerce.Money;

/**
 * payment-service 결제 취소 · 환불 실행 ({@code POST /internal/v1/payments/{paymentId}/cancellations}, {@code payment-guide.md} §8, #16 PR-2).
 * 환불액은 order-service 의 클레임 정책이 계산하고 payment 는 실행만 한다. {@code Idempotency-Key} 는 클레임 아이디다 — 미확정이면 같은 클레임 · 같은 금액으로 다시 부른다
 * (payment 는 같은 클레임의 성공 취소를 PG 호출 없이 200 으로 돌려준다. 키 유효 기간은 14일).
 *
 * <p>트랜잭션 안에서 부르지 않는다 — PG 승인 취소를 기다리는 긴 원격 호출이다.
 */
public interface PaymentCancelPort {

    /**
     * 원격 실패(4xx · 5xx · 타임아웃 · 서킷)는 예외 없이 {@link PaymentCancelQueryResult} 로 분류한다. 인자 위반은 {@link IllegalArgumentException} 이다.
     * Feign 이 아닌 {@link RuntimeException}(서비스 토큰 발급 실패 등)은 그대로 전파된다 — 호출자는 이를 UNKNOWN(나중에 같은 클레임으로 재시도)으로 다룬다.
     *
     * @param paymentId 환불할 결제 아이디 (양수)
     * @param claimId 취소 클레임 아이디 (양수) — 멱등 키
     * @param amount 환불액 (0보다 큼)
     */
    PaymentCancelQueryResult cancel(long paymentId, long claimId, Money amount);
}
