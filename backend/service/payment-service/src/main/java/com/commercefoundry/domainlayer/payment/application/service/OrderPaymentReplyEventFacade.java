package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReply;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplyOutcome;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderPaymentReplyProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderPaymentReplyEventFacade implements OrderPaymentReplyEventUseCase {

    private final OrderPaymentReplyProcessor orderPaymentReplyProcessor;

    /**
     * 원천 조회(order internal Feign) 동안 DB 커넥션을 쥐지 않도록 Facade 에는 트랜잭션을 걸지 않는다 — 반영 기록 + inbox 만 Processor 트랜잭션으로 좁히고,
     * 취소 정책 훅은 그 커밋 뒤에 부른다(훅이 PG 를 부르게 되어도 트랜잭션 밖이다, #579).
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void apply(OrderPaymentReplyCommand command) {
        if (orderPaymentReplyProcessor.isProcessed(command.eventId())) {
            return;
        }
        OrderPaymentReply reply = orderPaymentReplyProcessor.fetchSource(command);
        OrderPaymentReplyOutcome outcome = orderPaymentReplyProcessor.record(command, reply);
        orderPaymentReplyProcessor.requestCancel(outcome);
    }
}
