package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplyOutcome;

/** 기본 OFF Kafka 회신 리스너가 사용하는 원천 검증·원자 기록 진입 계약. */
public interface OrderPaymentReplyEventUseCase {
    OrderPaymentReplyOutcome handle(OrderPaymentReplyCommand command);
}
