package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.info.OrderFulfillmentInfo;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusTransitionException;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출고 · 배송 완료 반영 (#18) — delivery-service 의 internal 호출이 주문 상태를 바꾼다 ({@code order-flow-guide.md} §4).
 *
 * <p>주문마다 새 트랜잭션({@code REQUIRES_NEW}, 5초)이다 — 다시 읽고 도메인 {@code ship} · {@code deliver} 로 바꾼 뒤, 바뀌었을 때만 헤더 · {@code SERVICE} 이력을 쓴다.
 * 이미 출고 · 배송 완료된 뒤의 재요청은 아무것도 쓰지 않고 지금 상태를 돌려준다(송장 등록 재시도가 안전하다). 읽은 뒤 고객 취소 · 어드민 처리가 먼저 커밋했으면
 * merge 의 버전 비교가 {@code OptimisticLockingFailureException} 으로 이 트랜잭션을 되돌린다 — 둘 중 하나만 성공한다.
 *
 * <p>판정 순서: 없는 주문 404 → 이미 반영(멱등) 200 → 바꿀 수 없는 상태 409 {@code ORDER_020} → 시각 범위 400 {@code ORDER_159} · {@code 160}
 * → 배송 완료가 출고보다 앞섬 400 {@code ORDER_161} → 저장(버전 충돌 409 {@code ORDER_025}). 시각은 주문을 실제로 바꿀 때만 본다 — 이미 반영된 주문의
 * 재시도가 그 사이 흐른 시간 때문에 깨지지 않게.
 */
@Component
@RequiredArgsConstructor
public class OrderFulfillmentProcessor {

    /** 미래 허용 폭 — 서비스 간 시계 차이만큼만 받는다. 먼 미래 시각은 반품 기간 · 자동 구매 확정 기준({@code delivered_at})을 늦춘다. */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    /**
     * 과거 허용 폭 — 택배사 실제 시각이 늦게 들어오거나(추적 지연) 운영자가 밀린 송장을 늦게 등록하는 상한이다. 이보다 오래된 배송 완료 시각은 등록 즉시
     * 자동 구매 확정 기간(기본 7일, 최대 30일)을 지나 고객이 반품할 틈 없이 확정되므로 받지 않는다.
     */
    static final Duration MAX_PAST_DELAY = Duration.ofDays(30);

    private final OrderRepositoryPort orderRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /**
     * 출고 — 상품 준비중 → 배송 중, 출고 시각을 남긴다.
     *
     * @throws OrderException 없는 주문 {@code NOT_FOUND_ORDER} · 상품 준비중 · 출고 뒤가 아닌 상태 {@code ORDER_STATUS_TRANSITION_INVALID}
     * @throws OrderFieldValidationException 바꿀 때 출고 시각이 지금 기준 30일 전 ~ 5분 뒤 밖({@code ORDER_159})
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳이 먼저 바꿨을 때 (이 트랜잭션은 롤백)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public OrderFulfillmentInfo ship(long orderId, Instant shippedAt) {
        return apply(orderId, order -> order.ship(shippedAt),
            changed -> requireWithinWindow(shippedAt, "shippedAt", OrderValidationMessage.SHIPPED_AT_OUT_OF_RANGE), OrderStatusHistory.SHIPMENT_REGISTERED);
    }

    /**
     * 배송 완료 — 배송 중 → 배송 완료, 배송 완료 시각(자동 구매 확정 기준)을 남긴다.
     *
     * @throws OrderException 없는 주문 {@code NOT_FOUND_ORDER} · 배송 중 · 배송 완료 뒤가 아닌 상태 {@code ORDER_STATUS_TRANSITION_INVALID}
     * @throws OrderFieldValidationException 바꿀 때 배송 완료 시각이 지금 기준 30일 전 ~ 5분 뒤 밖({@code ORDER_160}) · 주문의 출고 시각보다 앞섬({@code ORDER_161})
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳이 먼저 바꿨을 때 (이 트랜잭션은 롤백)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public OrderFulfillmentInfo completeDelivery(long orderId, Instant deliveredAt) {
        return apply(orderId, order -> order.deliver(deliveredAt), changed -> {
            requireWithinWindow(deliveredAt, "deliveredAt", OrderValidationMessage.DELIVERED_AT_OUT_OF_RANGE);
            // 출고 시각이 없는 주문(칸이 생기기 전에 배송 중이 된 주문)은 견줄 것이 없다. 같은 시각은 받는다
            if (changed.shippedAt() != null && changed.deliveredAt().isBefore(changed.shippedAt())) {
                throw violation("deliveredAt", OrderValidationMessage.DELIVERED_AT_BEFORE_SHIPPED);
            }
        }, OrderStatusHistory.DELIVERY_COMPLETED);
    }

    private OrderFulfillmentInfo apply(long orderId, UnaryOperator<Order> transition, Consumer<Order> timeCheck, String reason) {
        Order order = orderRepositoryPort.findById(orderId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        Order changed;
        try {
            changed = transition.apply(order);
        } catch (OrderStatusTransitionException exception) {
            throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName());
        }
        if (changed == order) {
            return OrderFulfillmentInfo.of(order, false);
        }
        timeCheck.accept(changed);
        orderRepositoryPort.saveStatusChange(changed, OrderStatusHistory.changedByService(snowflakeIdGenerator.generateId(), order, changed, reason));
        return OrderFulfillmentInfo.of(changed, true);
    }

    private void requireWithinWindow(Instant value, String field, String message) {
        Instant now = clock.instant();
        if (value.isAfter(now.plus(MAX_FUTURE_SKEW)) || value.isBefore(now.minus(MAX_PAST_DELAY))) {
            throw violation(field, message);
        }
    }

    private static OrderFieldValidationException violation(String field, String message) {
        String[] parts = message.split(":", 2);
        return new OrderFieldValidationException(List.of(new OrderFieldValidationException.Violation(parts[0], field, parts[1])));
    }
}
