package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelVoidResponse;
import com.commercefoundry.shared.commerce.CurrencyCode;

/**
 * 결제 후 취소 클레임의 무환불 판정 · 봉인 (#691, {@code payment-guide.md} §8-1). 호출자는 order-service 하나다 — 어드민이 환불 실패 클레임을 거절하기 전에 부른다.
 * 판정(무환불 확정 · 환불됨 · 미확정)은 오류가 아니라 응답 데이터다. 같은 claimId 로 다시 부르면 그때의 판정을 새로 답한다.
 */
public interface PaymentCancellationVoidUseCase {

    /**
     * @param orderId 클레임이 속한 주문 — 결제의 주문과 같아야 한다
     * @param amount 접수 때 클레임에 저장한 환불액(최소 화폐 단위)
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException 404 {@code PAYMENT_001} 결제 없음 ·
     *     409 {@code PAYMENT_019} 결제의 주문이 다름 · claimId 양수 아님 · 자동 취소 claimId · 기존 취소 행과 결제 · 금액 · 통화가 다름 ·
     *     409 {@code PAYMENT_017} 통화가 결제와 다름 · 409 {@code PAYMENT_012} 봉인 INSERT 경합
     */
    PaymentCancelVoidResponse voidCancellation(long paymentId, long orderId, long claimId, long amount, CurrencyCode currency);
}
