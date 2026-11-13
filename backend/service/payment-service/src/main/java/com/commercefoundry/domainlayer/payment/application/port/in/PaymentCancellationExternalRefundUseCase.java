package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelExternalRefundResponse;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.shared.commerce.CurrencyCode;

/**
 * 외부 확인 환불 기록 (#693, {@code payment-guide.md} §8 "외부 확인 환불 기록 명령"). 호출자는 order-service 하나다 — 어드민이 환불 실패 클레임을
 * 수동 환불 확정하기 전에 부른다. 신고 채널은 PG 잔액으로 검증하고, 판정(기록됨 · 이미 환불됨 · 미확정)은 오류가 아니라 응답 데이터다.
 * 같은 claimId 로 다시 부르면 기록된 상태에서는 PG 를 다시 조회하지 않고 같은 답을 준다.
 */
public interface PaymentCancellationExternalRefundUseCase {

    /**
     * @param orderId 클레임이 속한 주문 — 결제의 주문과 같아야 한다
     * @param amount 접수 때 클레임에 저장한 환불액(최소 화폐 단위)
     * @param channel 운영자가 신고한 환불 경로
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException 404 {@code PAYMENT_001} 결제 없음 ·
     *     409 {@code PAYMENT_019} claimId 양수 아님 · 결제의 주문이 다름 · 자동 취소 claimId · 기존 취소 행과 결제 · 금액 · 통화가 다름 ·
     *     409 {@code PAYMENT_017} 통화가 결제와 다름 · 409 {@code PAYMENT_012} 기록 경합(INSERT 충돌 · 낙관 락)
     */
    PaymentCancelExternalRefundResponse recordExternalRefund(long paymentId, long orderId, long claimId, long amount, CurrencyCode currency, Channel channel);
}
