package com.commercefoundry.domainlayer.payment.application.exception;

import lombok.Getter;

/**
 * 같은 주문에 활성 결제 시도가 이미 있어 새 결제 시도를 저장하지 못했다 ({@code uk_payment_active_order_id} — {@code PaymentRepositoryPort#save}).
 * 동시 결제 준비가 겹쳤거나, 기존 시도를 버리지(ABORTED) 않고 새 시도를 만든 경우다.
 *
 * <p>포트 계약의 예외다 — 결제 오류 코드(3종 세트)는 결제 API 와 함께 생기므로 그 Processor 가 이 예외를 받아 업무 오류(409)로 바꾼다.
 * 원인 예외를 싣지 않는다 — DB 오류 메시지가 예외 메시지 · 스택으로 퍼지지 않게 제약 이름만 남긴다 ({@code coding-conventions.md} §8, #173 2차 방어).
 */
@Getter
public class ActivePaymentConflictException extends RuntimeException {

    private final long orderId;

    public ActivePaymentConflictException(long orderId) {
        super("주문에 활성 결제 시도가 이미 있습니다. orderId=" + orderId + ", constraint=uk_payment_active_order_id");
        this.orderId = orderId;
    }
}
