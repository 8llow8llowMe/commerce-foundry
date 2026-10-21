package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.UUID;

/** 주문 생성 테스트 값 — 모두 가짜다(실제 사람 · 주소가 아니다). */
public final class OrderCreateFixtures {

    public static final long MEMBER_ID = 4611686018427387904L;
    public static final UUID KEY = UUID.fromString("3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11");

    private OrderCreateFixtures() {
    }

    public static OrderCreateCommand create(List<OrderItemCommand> items) {
        return new OrderCreateCommand(items, OrderFixtures.address(OrderFixtures.KR), null);
    }

    public static OrderCreateCommand create(List<OrderItemCommand> items, String countryCode, Money expectedTotalAmount) {
        return new OrderCreateCommand(items, OrderFixtures.address(countryCode), expectedTotalAmount);
    }
}
