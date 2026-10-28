package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 허용 전이 표 전체 — 표에 있는 전이는 허용, 그 밖은 모두 거절 ({@code order-flow-guide.md} §1). */
class OrderStatusTransitionTest {

    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.ofEntries(
        Map.entry(OrderStatus.PENDING_PAYMENT, EnumSet.of(OrderStatus.PAID, OrderStatus.EXPIRED)),
        Map.entry(OrderStatus.EXPIRED, EnumSet.of(OrderStatus.PAID)),
        Map.entry(OrderStatus.PAID, EnumSet.of(OrderStatus.MAKING, OrderStatus.PREPARING, OrderStatus.CANCEL_REQUESTED)),
        Map.entry(OrderStatus.MAKING, EnumSet.of(OrderStatus.PREPARING, OrderStatus.CANCEL_REQUESTED)),
        Map.entry(OrderStatus.PREPARING, EnumSet.of(OrderStatus.SHIPPING, OrderStatus.CANCEL_REQUESTED)),
        Map.entry(OrderStatus.SHIPPING, EnumSet.of(OrderStatus.DELIVERED)),
        Map.entry(OrderStatus.DELIVERED, EnumSet.of(OrderStatus.PURCHASE_CONFIRMED, OrderStatus.RETURN_REQUESTED)),
        Map.entry(OrderStatus.PURCHASE_CONFIRMED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.CANCEL_REQUESTED, EnumSet.of(OrderStatus.CANCELED)),
        Map.entry(OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.RETURN_REQUESTED, EnumSet.of(OrderStatus.RETURNED)),
        Map.entry(OrderStatus.RETURNED, EnumSet.noneOf(OrderStatus.class)));

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("표에 있는 전이만 허용하고 나머지(자기 자신 포함)는 거절한다")
    void transitions(OrderStatus from) {
        for (OrderStatus to : OrderStatus.values()) {
            assertThat(from.canTransitionTo(to)).as("%s → %s", from, to).isEqualTo(EXPECTED.get(from).contains(to));
        }
        assertThat(from.nextStatuses()).containsExactlyInAnyOrderElementsOf(EXPECTED.get(from));
    }

    @Test
    @DisplayName("상태 표시는 metadata 로 나간다")
    void metadata() {
        assertThat(OrderStatus.PENDING_PAYMENT.toMetadata().code()).isEqualTo("PENDING_PAYMENT");
        assertThat(OrderStatus.PENDING_PAYMENT.toMetadata().name()).isEqualTo("결제 대기");
    }
}
