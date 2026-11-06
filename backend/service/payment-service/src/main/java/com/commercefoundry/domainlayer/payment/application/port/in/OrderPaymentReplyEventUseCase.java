package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplyOutcome;

/** 생산 리스너가 연결되지 않은 회신 기록 진입 계약. */
public interface OrderPaymentReplyEventUseCase {
    OrderPaymentReplyOutcome handle(OrderPaymentReplyCommand command);
}
