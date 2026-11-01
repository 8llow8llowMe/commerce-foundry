package com.commercefoundry.domainlayer.order.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 어드민 화면이 직접 바꾸는 전이 (#17, {@code order-flow-guide.md} §1 · §6). 전이 표({@link OrderStatus}) 안에서도 다른 흐름이 소유한 전이는 막는다 —
 * 어드민이라고 아무 상태로나 바꾸지 않는다.
 *
 * <table>
 *   <caption>어드민 직접 전이</caption>
 *   <tr><th>현재</th><th>다음</th></tr>
 *   <tr><td>PAID</td><td>MAKING(맞춤 제작 항목이 있을 때만) · PREPARING</td></tr>
 *   <tr><td>MAKING</td><td>PREPARING</td></tr>
 *   <tr><td>SHIPPING</td><td>DELIVERED — 택배사 추적이 없을 때 운영자가 확인해 둔다</td></tr>
 * </table>
 *
 * 다른 흐름의 전이: 결제 완료 PAID(결제 #14), 만료 EXPIRED(만료 스윕 #96), 출고 SHIPPING(송장 등록 #18 — 송장과 함께 바뀐다),
 * 취소 · 반품 요청과 완료(클레임 #16 · 환불 #15), 구매 확정(고객 · 스케줄러).
 */
public final class AdminStatusChangePolicy {

    private static final Map<OrderStatus, Set<OrderStatus>> MANUAL = manual();

    private AdminStatusChangePolicy() {
    }

    /** 판정 결과. */
    public enum Decision {
        /** 바꿔도 된다. */
        ALLOWED,
        /** 이미 그 상태다 — 바꾸지 않고 성공으로 본다(재시도 · 일괄 재실행이 안전하다). */
        ALREADY,
        /** 전이 표 밖이다. */
        NOT_IN_TABLE,
        /** 표 안이지만 다른 흐름이 바꾸는 전이다. */
        NOT_MANUAL,
        /** 맞춤 제작 항목이 없는데 제작 중으로 바꾸려 했다. */
        MAKING_WITHOUT_CUSTOM_ITEM
    }

    public static Decision decide(Order order, OrderStatus target) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(target, "target");
        OrderStatus current = order.status();
        if (current == target) {
            return Decision.ALREADY;
        }
        if (!current.canTransitionTo(target)) {
            return Decision.NOT_IN_TABLE;
        }
        if (!MANUAL.get(current).contains(target)) {
            return Decision.NOT_MANUAL;
        }
        if (target == OrderStatus.MAKING && !order.hasCustomOrderItem()) {
            return Decision.MAKING_WITHOUT_CUSTOM_ITEM;
        }
        return Decision.ALLOWED;
    }

    /** 이 주문을 어드민이 지금 바꿀 수 있는 다음 상태 — 화면이 버튼을 그린다. */
    public static Set<OrderStatus> manualTargets(Order order) {
        EnumSet<OrderStatus> targets = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus target : MANUAL.get(order.status())) {
            if (decide(order, target) == Decision.ALLOWED) {
                targets.add(target);
            }
        }
        return Collections.unmodifiableSet(targets);
    }

    private static Map<OrderStatus, Set<OrderStatus>> manual() {
        Map<OrderStatus, Set<OrderStatus>> table = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            table.put(status, Collections.unmodifiableSet(EnumSet.noneOf(OrderStatus.class)));
        }
        table.put(OrderStatus.PAID, Collections.unmodifiableSet(EnumSet.of(OrderStatus.MAKING, OrderStatus.PREPARING)));
        table.put(OrderStatus.MAKING, Collections.unmodifiableSet(EnumSet.of(OrderStatus.PREPARING)));
        table.put(OrderStatus.SHIPPING, Collections.unmodifiableSet(EnumSet.of(OrderStatus.DELIVERED)));
        // 표 밖 전이가 끼지 않았는지 기동 때 확인한다
        table.forEach((from, targets) -> targets.forEach(to -> {
            if (!from.canTransitionTo(to)) {
                throw new IllegalStateException("어드민 전이가 전이 표 밖이다: " + from + " -> " + to);
            }
        }));
        return Collections.unmodifiableMap(table);
    }
}
