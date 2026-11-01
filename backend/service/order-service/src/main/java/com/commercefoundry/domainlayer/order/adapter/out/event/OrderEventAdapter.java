package com.commercefoundry.domainlayer.order.adapter.out.event;

import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.messaging.publish.DomainEvent;
import com.commercefoundry.messaging.publish.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 주문 이벤트를 messaging-core {@link EventPublisher} 로 outbox({@code orders.outbox})에 넣는다. 메시징이 꺼져 있어도 저장은 하고 릴레이만 멈춘다.
 * 토픽은 {@code <namespace>.order.order-expired}, 키는 주문 아이디다 ({@code order-flow-guide.md} §3-5).
 */
@Component
@RequiredArgsConstructor
public class OrderEventAdapter implements OrderEventPort {

    private final EventPublisher eventPublisher;

    @Override
    public void expired(long orderId, long reservationId) {
        eventPublisher.publish(new DomainEvent("order", Long.toString(orderId), "order-expired", 1,
            new OrderStockReleaseMessage(Long.toString(orderId), Long.toString(reservationId), "PAYMENT_TIMEOUT")));
    }

    @Override
    public void canceled(long orderId, long reservationId, OrderCancelReason reason) {
        eventPublisher.publish(new DomainEvent("order", Long.toString(orderId), "order-canceled", 1,
            new OrderStockReleaseMessage(Long.toString(orderId), Long.toString(reservationId), reason.name())));
    }

    /** {@code order-expired} · {@code order-canceled} v1 페이로드(두 이벤트가 같은 모양, §3-5) — 식별자는 문자열이다(JS 정밀도). 필드를 바꾸면 스키마 버전을 올린다. */
    public record OrderStockReleaseMessage(String orderId, String reservationId, String reason) {
    }
}
