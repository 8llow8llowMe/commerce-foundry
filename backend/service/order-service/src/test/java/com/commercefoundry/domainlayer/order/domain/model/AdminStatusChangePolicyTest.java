package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.order.domain.model.AdminStatusChangePolicy.Decision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 어드민 직접 전이 정책 (#17) — 전이 표 안에서도 다른 흐름의 전이는 막는다. */
class AdminStatusChangePolicyTest {

    private static Order order(OrderStatus status, boolean customOrder) {
        OrderQuoteLine line = OrderQuoteLine.of(101, 7101, "상품", "옵션", krw(1000), 1, OrderCustomOptions.empty(), customOrder, WEIGHT_GRAMS, HS_CODE);
        return new Order(1, 2, status, domesticQuote(line), List.of(3L), address("KR"), 4, Instant.parse("2026-10-18T09:30:00Z"), null, null, null, 0L);
    }

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource({
        "PAID, PREPARING, ALLOWED",
        "MAKING, PREPARING, ALLOWED",
        "SHIPPING, DELIVERED, ALLOWED",
        "PAID, PAID, ALREADY",
        "PENDING_PAYMENT, PAID, NOT_MANUAL",
        "PENDING_PAYMENT, EXPIRED, NOT_MANUAL",
        "PENDING_PAYMENT, CANCELED, NOT_MANUAL",
        "EXPIRED, PAID, NOT_MANUAL",
        "PREPARING, SHIPPING, NOT_MANUAL",
        "PAID, CANCEL_REQUESTED, NOT_MANUAL",
        "CANCEL_REQUESTED, CANCELED, NOT_MANUAL",
        "CANCEL_REQUESTED, PAID, NOT_MANUAL",
        "CANCEL_REQUESTED, PREPARING, NOT_MANUAL",
        "DELIVERED, PURCHASE_CONFIRMED, NOT_MANUAL",
        "RETURN_REQUESTED, RETURNED, NOT_MANUAL",
        "PENDING_PAYMENT, PREPARING, NOT_IN_TABLE",
        "PREPARING, PAID, NOT_IN_TABLE",
        "CANCELED, PREPARING, NOT_IN_TABLE",
        "PAID, MAKING, MAKING_WITHOUT_CUSTOM_ITEM"
    })
    void decides(OrderStatus current, OrderStatus target, Decision expected) {
        assertThat(AdminStatusChangePolicy.decide(order(current, false), target)).isEqualTo(expected);
    }

    @Test
    @DisplayName("맞춤 제작 항목이 있으면 결제 완료에서 제작 중으로 갈 수 있고, 다음 상태 목록에도 들어간다")
    void makingForCustomOrders() {
        assertThat(AdminStatusChangePolicy.decide(order(OrderStatus.PAID, true), OrderStatus.MAKING)).isEqualTo(Decision.ALLOWED);
        assertThat(AdminStatusChangePolicy.manualTargets(order(OrderStatus.PAID, true))).containsExactlyInAnyOrder(OrderStatus.MAKING, OrderStatus.PREPARING);
        assertThat(AdminStatusChangePolicy.manualTargets(order(OrderStatus.PAID, false))).containsExactly(OrderStatus.PREPARING);
        assertThat(AdminStatusChangePolicy.manualTargets(order(OrderStatus.PENDING_PAYMENT, false))).isEmpty();
    }

    @Test
    @DisplayName("도메인 changeStatus 는 표 밖 전이 · 저장 전 주문을 거절한다")
    void domainGuard() {
        assertThatThrownBy(() -> order(OrderStatus.PAID, false).changeStatus(OrderStatus.DELIVERED)).isInstanceOf(OrderStatusTransitionException.class);
        Order unsaved = Order.place(9, 2, domesticQuote(quoteLine(101, 1000, 1)), () -> 10L, address("KR"), 11, Instant.parse("2026-10-18T09:30:00Z"));
        assertThatThrownBy(() -> unsaved.changeStatus(OrderStatus.EXPIRED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThat(order(OrderStatus.PAID, false).changeStatus(OrderStatus.PREPARING).status()).isEqualTo(OrderStatus.PREPARING);
    }
}
