package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult;
import com.commercefoundry.shared.commerce.Money;

/**
 * payment-service 결제 취소 · 환불 실행 ({@code POST /internal/v1/payments/{paymentId}/cancellations}, {@code payment-guide.md} §8, #16 PR-2)과
 * 취소 거절 전 무환불 판정({@link #confirmNoRefund}, #17 A4).
 * 환불액은 order-service 의 클레임 정책이 계산하고 payment 는 실행만 한다. {@code Idempotency-Key} 는 클레임 아이디다 — 미확정이면 같은 클레임 · 같은 금액으로 다시 부른다
 * (payment 는 같은 클레임의 성공 취소를 PG 호출 없이 200 으로 돌려준다. 키 유효 기간은 14일).
 *
 * <p>트랜잭션 안에서 부르지 않는다 — PG 승인 취소 · PG 잔액 조회를 기다리는 긴 원격 호출이다.
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

    /**
     * 결제 후 취소 클레임의 무환불 판정 · 봉인 ({@code POST /internal/v1/payments/{paymentId}/cancellations/{claimId}/void}, {@code payment-guide.md} §8-1, #17 A4).
     * 취소 거절 전에 부른다 — 결제사 거절 코드({@code PAYMENT_016 · 017 · 019})는 무환불 증거가 아니다. 결과가
     * {@link PaymentCancelVoidQueryResult.Outcome#NO_REFUND_CONFIRMED} 일 때만 거절한다. 멱등 키 헤더는 없고 경로의 클레임 아이디가 키다 —
     * 다시 부르면 payment 가 PG 잔액까지 새로 대조한 지금 판정을 준다. 어드민 동작 1회당 1회만 부르고 자동 재시도 루프에 붙이지 않는다
     * (payment 의 PG 조회가 결제 승인과 같은 서킷을 쓴다).
     *
     * <p>원격 실패(4xx · 5xx · 타임아웃 · 서킷 · 계약 위반)는 예외 없이 {@link PaymentCancelVoidQueryResult} 로 분류한다. 인자 위반은 {@link IllegalArgumentException} 이다.
     * Feign 이 아닌 {@link RuntimeException}(서비스 토큰 발급 실패 등)은 그대로 전파된다 — 호출자는 거절하지 않는다.
     *
     * @param paymentId 클레임에 저장된 결제 아이디 (양수) — 주문의 결제 기록과 같은지 부르는 쪽이 먼저 본다
     * @param orderId 클레임이 속한 주문 아이디 (양수) — payment 가 결제의 주문과 대조한다(다르면 409 {@code PAYMENT_019})
     * @param claimId 취소 클레임 아이디 (양수)
     * @param amount 접수 때 클레임에 저장한 환불액 (0보다 큼) — 다시 계산하지 않는다
     */
    PaymentCancelVoidQueryResult confirmNoRefund(long paymentId, long orderId, long claimId, Money amount);
}
