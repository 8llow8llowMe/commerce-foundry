package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.US;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

    private static final Instant DUE = Instant.parse("2026-10-18T09:30:00Z");
    private static final ShippingAddress ADDRESS = address("KR");
    private static final OrderQuote QUOTE = domesticQuote(quoteLine(9001, 10_000, 2), quoteLine(9002, 5_000, 1));

    private static Order placed() {
        AtomicLong ids = new AtomicLong(10);
        return Order.place(1L, 7L, QUOTE, ids::incrementAndGet, ADDRESS, 99L, DUE);
    }

    private static Order withStatus(OrderStatus status) {
        Order order = placed();
        return new Order(order.id(), order.memberId(), status, order.quote(), order.itemIds(), order.shippingAddress(),
            order.stockReservationId(), order.paymentDueAt(), Instant.EPOCH, 3L);
    }

    @Test
    @DisplayName("place 는 결제 대기로 시작하고 버전 · 생성 시각이 없다 — 결제 기한은 받은 선점 만료 시각, 항목 아이디는 줄마다 하나씩 발급한다")
    void place() {
        Order order = placed();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.isNew()).isTrue();
        assertThat(order.createdAt()).isNull();
        assertThat(order.paymentDueAt()).isEqualTo(DUE);
        assertThat(order.quote().totalAmount()).isEqualTo(krw(28_000));
        assertThat(order.itemIds()).containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("expire(now) — 결제 대기이고 기한이 지났을 때(같은 시각 포함)만 EXPIRED 다. 기한 전이면 그대로다")
    void expireWhenDue() {
        Order order = placed();

        assertThat(order.expire(DUE.minusSeconds(1))).isSameAs(order);
        assertThat(order.expire(DUE).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.expire(DUE.plusSeconds(60)).status()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    @DisplayName("이미 EXPIRED 면 바꾸지 않은 결과를 돌려준다 — 만료 스윕이 두 번 봐도 안전하다")
    void expireIsIdempotent() {
        Order expired = placed().expire(DUE);

        assertThat(expired.expire(DUE.plusSeconds(60))).isSameAs(expired);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PENDING_PAYMENT", "EXPIRED"})
    @DisplayName("결제 대기 · 만료가 아닌 주문의 만료는 도메인 전이 예외다 — Processor 가 ORDER_STATUS_TRANSITION_INVALID 로 바꾼다")
    void expireRejected(OrderStatus status) {
        assertThatThrownBy(() -> withStatus(status).expire(DUE.plusSeconds(60))).isInstanceOfSatisfying(OrderStatusTransitionException.class, exception -> {
            assertThat(exception.getCurrentStatus()).isEqualTo(status);
            assertThat(exception.getTargetStatus()).isEqualTo(OrderStatus.EXPIRED);
        });
    }

    @Test
    @DisplayName("배송지 국가가 주문서의 배송 국가와 다르거나 항목 아이디 수가 줄 수와 다르면 만들지 않는다")
    void invariants() {
        assertThatThrownBy(() -> Order.place(1L, 7L, QUOTE, () -> 1L, address(US), 99L, DUE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order(1L, 7L, OrderStatus.PENDING_PAYMENT, QUOTE, List.of(1L), ADDRESS, 99L, DUE, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("문자열 표현에 배송지(이름 · 연락처 · 우편번호 · 주소)가 없다 — 로그로 새지 않는다")
    void toStringHidesPersonalData() {
        Order order = placed();

        assertThat(order.toString()).doesNotContain("테스트수령인", "010-0000-0001", "63100", "테스트로");
        assertThat(order.shippingAddress().toString()).doesNotContain("테스트수령인", "010-0000-0001", "63100", "테스트로");
    }
}
