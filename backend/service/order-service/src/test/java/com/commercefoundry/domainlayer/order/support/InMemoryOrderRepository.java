package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 영속성 포트의 계약을 메모리로 흉내 낸다 — 같은 주문 아이디 · 같은 재고 선점은 유니크 위반처럼 {@code ALREADY_SAVED} 로 거절한다. 다음 저장의 실패를 미리 넣을 수 있다.
 * 실제 질의는 리포지토리 테스트(Testcontainers)가 본다.
 */
public class InMemoryOrderRepository implements OrderRepositoryPort {

    public static final Instant CREATED_AT = Instant.parse("2026-10-18T09:00:00Z");

    private final Map<Long, Order> orders = new TreeMap<>();
    private final List<OrderStatusHistory> histories = new ArrayList<>();
    private final Deque<RuntimeException> createFailures = new ArrayDeque<>();
    private int createCalls;

    @Override
    public Order insert(Order order, OrderStatusHistory history) {
        createCalls++;
        if (!createFailures.isEmpty()) {
            throw createFailures.poll();
        }
        if (orders.containsKey(order.id()) || orders.values().stream().anyMatch(saved -> saved.stockReservationId() == order.stockReservationId())) {
            throw new OrderSaveException(OrderSaveException.Reason.ALREADY_SAVED, "ConstraintViolationException");
        }
        Order saved = new Order(order.id(), order.memberId(), order.status(), order.quote(), order.itemIds(), order.shippingAddress(),
            order.stockReservationId(), order.paymentDueAt(), CREATED_AT, 0L);
        orders.put(saved.id(), saved);
        histories.add(history);
        return saved;
    }

    @Override
    public Optional<Order> findByIdAndMemberId(long orderId, long memberId) {
        return Optional.ofNullable(orders.get(orderId)).filter(order -> order.memberId() == memberId);
    }

    public InMemoryOrderRepository failNextCreate(RuntimeException... failures) {
        createFailures.addAll(List.of(failures));
        return this;
    }

    public List<Order> all() {
        return List.copyOf(orders.values());
    }

    public List<OrderStatusHistory> histories() {
        return histories;
    }

    public int createCalls() {
        return createCalls;
    }
}
