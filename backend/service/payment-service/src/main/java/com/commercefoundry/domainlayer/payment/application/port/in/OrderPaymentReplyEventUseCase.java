package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;

/**
 * 주문 결제 반영 회신을 결제의 주문 반영 기록({@code order_synced} · {@code order_sync_failure_code})에 남긴다 (#308 PR-C, {@code payment-guide.md} §6).
 * 같은 이벤트는 한 번만 반영한다. 재시도로 바뀔 수 있는 실패는 예외 그대로, 다시 해도 같은 실패는 {@code NonRetryableEventException} 으로 던진다.
 */
public interface OrderPaymentReplyEventUseCase {

    void apply(OrderPaymentReplyCommand command);
}
