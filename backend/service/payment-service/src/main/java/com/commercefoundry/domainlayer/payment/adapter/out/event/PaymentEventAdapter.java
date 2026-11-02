package com.commercefoundry.domainlayer.payment.adapter.out.event;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentEventPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.messaging.publish.DomainEvent;
import com.commercefoundry.messaging.publish.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제 이벤트를 messaging-core {@link EventPublisher} 로 outbox({@code payment.outbox})에 넣는다. 메시징이 꺼져 있어도 저장은 하고 릴레이만 멈춘다.
 * 토픽은 {@code <namespace>.payment.payment-approved}, 키는 주문 아이디다 — 같은 주문의 결제 이벤트가 한 파티션에서 순서대로 간다 ({@code payment-guide.md} §6).
 */
@Component
@RequiredArgsConstructor
public class PaymentEventAdapter implements PaymentEventPort {

    private final EventPublisher eventPublisher;

    @Override
    public void approved(Payment done) {
        if (done.status() != PaymentStatus.DONE) {
            // 호출 실수다 — 승인되지 않은 결제를 승인 이벤트로 내보내지 않는다
            throw new IllegalArgumentException("DONE 결제만 승인 이벤트로 낸다: " + done.status());
        }
        String orderId = Long.toString(done.orderId());
        PaymentApprovedMessage message = new PaymentApprovedMessage(
            Long.toString(done.id()), orderId, Long.toString(done.memberId()), done.amount().amount(), done.amount().currency().name(),
            done.approvedAt(), done.method().name()
        );
        eventPublisher.publish(new DomainEvent(
            PaymentApprovedMessage.AGGREGATE_TYPE, orderId, PaymentApprovedMessage.EVENT_TYPE, PaymentApprovedMessage.SCHEMA_VERSION, message
        ));
    }
}
