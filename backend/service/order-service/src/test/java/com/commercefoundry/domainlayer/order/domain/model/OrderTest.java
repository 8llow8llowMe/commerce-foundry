package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.US;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.ReturnOutcome;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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
            order.stockReservationId(), order.paymentDueAt(), null, null, Instant.EPOCH, 3L);
    }

    @Test
    @DisplayName("cancelBeforePayment — 결제 대기면 CANCELED(나머지 값 그대로), 이미 취소면 그대로다. 고객 취소 가능은 결제 대기만이다")
    void cancelBeforePayment() {
        Order pending = withStatus(OrderStatus.PENDING_PAYMENT);
        Order canceled = pending.cancelBeforePayment();

        assertThat(pending.isCancelableBeforePayment()).isTrue();
        assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled).usingRecursiveComparison().ignoringFields("status").isEqualTo(pending);
        assertThat(canceled.isCancelableBeforePayment()).isFalse();
        // 일반 상태 변경으로는 결제 전 취소를 못 한다 — 재고 해제 이벤트 없이 CANCELED 가 되지 않게
        assertThatThrownBy(() -> pending.changeStatus(OrderStatus.CANCELED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThat(canceled.cancelBeforePayment()).isSameAs(canceled);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PENDING_PAYMENT", "CANCELED"})
    @DisplayName("결제 대기 · 취소 완료가 아닌 주문의 결제 전 취소는 도메인 전이 예외다 — 결제 뒤 취소는 다른 흐름(#16 PR-2)이다")
    void cancelBeforePaymentRejected(OrderStatus status) {
        assertThat(withStatus(status).isCancelableBeforePayment()).isFalse();
        assertThatThrownBy(() -> withStatus(status).cancelBeforePayment()).isInstanceOfSatisfying(OrderStatusTransitionException.class, exception -> {
            assertThat(exception.getCurrentStatus()).isEqualTo(status);
            assertThat(exception.getTargetStatus()).isEqualTo(OrderStatus.CANCELED);
        });
    }

    @Test
    @DisplayName("결제 전 취소 이력은 저장된 결제 대기 주문에서 고객 · 사유 코드로 만든다")
    void customerCancelHistory() {
        Order pending = withStatus(OrderStatus.PENDING_PAYMENT);
        OrderStatusHistory history = OrderStatusHistory.canceledByCustomer(11L, pending, OrderCancelReason.ORDER_MISTAKE);

        assertThat(history.fromStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(history.toStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(history.changedByType()).isEqualTo(StatusChangedBy.CUSTOMER);
        assertThat(history.changedById()).isEqualTo(pending.memberId());
        assertThat(history.reason()).isEqualTo("ORDER_MISTAKE");
        assertThatThrownBy(() -> OrderStatusHistory.canceledByCustomer(12L, placed(), OrderCancelReason.OTHER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderStatusHistory.canceledByCustomer(12L, withStatus(OrderStatus.PAID), OrderCancelReason.OTHER))
            .isInstanceOf(IllegalArgumentException.class);
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
    @DisplayName("결제 기한은 마이크로초로 자른다 — DB(timestamp(6))에서 다시 읽은 재생 응답과 새 주문 응답의 기한이 같다")
    void placeTruncatesPaymentDueAtToMicros() {
        Order order = Order.place(1L, 7L, QUOTE, new AtomicLong(10)::incrementAndGet, ADDRESS, 99L, Instant.parse("2026-10-18T09:30:00.123456789Z"));

        assertThat(order.paymentDueAt()).isEqualTo(Instant.parse("2026-10-18T09:30:00.123456Z"));
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
        assertThatThrownBy(() -> new Order(1L, 7L, OrderStatus.PENDING_PAYMENT, QUOTE, List.of(1L), ADDRESS, 99L, DUE, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("생성 이력은 null → PENDING_PAYMENT, 고객 · 회원 아이디다. 바꾼 사람 아이디는 CUSTOMER · ADMIN 에만 있다")
    void historyActor() {
        OrderStatusHistory created = OrderStatusHistory.created(5L, placed());

        assertThat(created.fromStatus()).isNull();
        assertThat(created.toStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(created.changedByType()).isEqualTo(StatusChangedBy.CUSTOMER);
        assertThat(created.changedById()).isEqualTo(7L);
        assertThat(new OrderStatusHistory(6L, 1L, OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED, StatusChangedBy.SYSTEM, null, null).changedById()).isNull();
        assertThatThrownBy(() -> new OrderStatusHistory(6L, 1L, null, OrderStatus.PENDING_PAYMENT, StatusChangedBy.CUSTOMER, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderStatusHistory(6L, 1L, OrderStatus.PAID, OrderStatus.PREPARING, StatusChangedBy.ADMIN, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderStatusHistory(6L, 1L, OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, StatusChangedBy.SERVICE, 7L, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("문자열 표현에 배송지(이름 · 연락처 · 우편번호 · 주소)가 없다 — 로그로 새지 않는다")
    void toStringHidesPersonalData() {
        Order order = placed();

        assertThat(order.toString()).doesNotContain("테스트수령인", "01000000001", "63100", "테스트로");
        assertThat(order.shippingAddress().toString()).doesNotContain("테스트수령인", "01000000001", "63100", "테스트로");
    }
    @Test
    void creationHistoryRejectsStoredOrNonPendingOrders() {
        assertThatThrownBy(() -> OrderStatusHistory.created(10L, withStatus(OrderStatus.PAID))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderStatusHistory.created(10L, withStatus(OrderStatus.PENDING_PAYMENT))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("만료 이력은 저장된 결제 대기 주문에서만 만든다 — PENDING_PAYMENT → EXPIRED, SYSTEM, 바꾼 사람 없음, 사유 PAYMENT_TIMEOUT")
    void expiryHistoryIsSystemPaymentTimeoutFromStoredPendingOrder() {
        Order pending = withStatus(OrderStatus.PENDING_PAYMENT);

        OrderStatusHistory expired = OrderStatusHistory.expired(11L, pending);

        assertThat(expired.orderId()).isEqualTo(pending.id());
        assertThat(expired.fromStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(expired.toStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.changedByType()).isEqualTo(StatusChangedBy.SYSTEM);
        assertThat(expired.changedById()).isNull();
        assertThat(expired.reason()).isEqualTo("PAYMENT_TIMEOUT");
        assertThatThrownBy(() -> OrderStatusHistory.expired(11L, placed())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderStatusHistory.expired(11L, withStatus(OrderStatus.PAID))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderStatusHistory.expired(11L, withStatus(OrderStatus.EXPIRED))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyNeverPrintsReason() {
        OrderStatusHistory history = new OrderStatusHistory(1, 2, null, OrderStatus.PENDING_PAYMENT, StatusChangedBy.CUSTOMER, 7L, "private address memo");
        assertThat(history.toString()).doesNotContain("private address memo");
        assertThatThrownBy(() -> new OrderStatusHistory(1, 2, null, OrderStatus.PENDING_PAYMENT, StatusChangedBy.CUSTOMER, 7L, "x".repeat(201)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("x".repeat(201));
    }

    @Test
    void placeRejectsDuplicateOrNonPositiveItemIdentifiers() {
        assertThatThrownBy(() -> Order.place(1, 7, QUOTE, () -> 11, ADDRESS, 99, DUE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(0, 7, QUOTE, new AtomicLong(10)::incrementAndGet, ADDRESS, 99, DUE)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final Instant SHIPPED_AT = Instant.parse("2026-11-02T09:30:00.123456789Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-11-04T05:00:00.987654321Z");

    @Test
    @DisplayName("ship — 상품 준비중이면 배송 중이 되고 출고 시각을 마이크로초로 잘라 남긴다(나머지 값 그대로). 다시 부르면 그대로다(처음 출고 시각 유지)")
    void ship() {
        Order preparing = withStatus(OrderStatus.PREPARING);

        Order shipped = preparing.ship(SHIPPED_AT);

        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(shipped.shippedAt()).isEqualTo(Instant.parse("2026-11-02T09:30:00.123456Z"));
        assertThat(shipped.deliveredAt()).isNull();
        assertThat(shipped).usingRecursiveComparison().ignoringFields("status", "shippedAt").isEqualTo(preparing);
        assertThat(shipped.ship(SHIPPED_AT.plusSeconds(3600))).isSameAs(shipped);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPING", "DELIVERED", "PURCHASE_CONFIRMED", "RETURN_REQUESTED", "RETURNED"})
    @DisplayName("ship — 이미 출고된 뒤(배송 중 · 배송 완료 · 구매 확정 · 반품)면 바꾸지 않은 이 값이다 — 송장 등록 재시도가 안전하다")
    void shipIsIdempotentAfterShipment(OrderStatus status) {
        Order order = withStatus(status);

        assertThat(order.ship(SHIPPED_AT)).isSameAs(order);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "EXPIRED", "PAID", "MAKING", "CANCEL_REQUESTED", "CANCELED"})
    @DisplayName("ship — 상품 준비중 전이거나 취소된 주문은 도메인 전이 예외다(목표 SHIPPING)")
    void shipRejected(OrderStatus status) {
        assertThatThrownBy(() -> withStatus(status).ship(SHIPPED_AT)).isInstanceOfSatisfying(OrderStatusTransitionException.class, exception -> {
            assertThat(exception.getCurrentStatus()).isEqualTo(status);
            assertThat(exception.getTargetStatus()).isEqualTo(OrderStatus.SHIPPING);
        });
    }

    @Test
    @DisplayName("deliver — 배송 중이면 배송 완료가 되고 배송 완료 시각을 마이크로초로 잘라 남긴다(출고 시각 유지). 다시 부르면 그대로다")
    void deliver() {
        Order shipping = withStatus(OrderStatus.PREPARING).ship(SHIPPED_AT);

        Order delivered = shipping.deliver(DELIVERED_AT);

        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).isEqualTo(Instant.parse("2026-11-04T05:00:00.987654Z"));
        assertThat(delivered.shippedAt()).isEqualTo(shipping.shippedAt());
        assertThat(delivered).usingRecursiveComparison().ignoringFields("status", "deliveredAt").isEqualTo(shipping);
        assertThat(delivered.deliver(DELIVERED_AT.plusSeconds(60))).isSameAs(delivered);
    }

    @Test
    @DisplayName("deliver — 출고 시각이 없는 배송 중 주문(칸이 생기기 전 출고)도 배송 완료할 수 있다 — 출고 시각은 null 그대로다")
    void deliverWithoutShippedAt() {
        Order delivered = withStatus(OrderStatus.SHIPPING).deliver(DELIVERED_AT);

        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.shippedAt()).isNull();
        assertThat(delivered.deliveredAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "PURCHASE_CONFIRMED", "RETURN_REQUESTED", "RETURNED"})
    @DisplayName("deliver — 이미 배송 완료된 뒤면 바꾸지 않은 이 값이다")
    void deliverIsIdempotentAfterDelivery(OrderStatus status) {
        Order order = withStatus(status);

        assertThat(order.deliver(DELIVERED_AT)).isSameAs(order);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "EXPIRED", "PAID", "MAKING", "PREPARING", "CANCEL_REQUESTED", "CANCELED"})
    @DisplayName("deliver — 배송 중 전이거나 취소된 주문은 도메인 전이 예외다(목표 DELIVERED) — 상품 준비중에서 바로 배송 완료하지 않는다")
    void deliverRejected(OrderStatus status) {
        assertThatThrownBy(() -> withStatus(status).deliver(DELIVERED_AT)).isInstanceOfSatisfying(OrderStatusTransitionException.class, exception -> {
            assertThat(exception.getCurrentStatus()).isEqualTo(status);
            assertThat(exception.getTargetStatus()).isEqualTo(OrderStatus.DELIVERED);
        });
    }

    @Test
    @DisplayName("멱등 범위는 전이 표와 같다 — ship 은 배송 중에서, deliver 는 배송 완료에서 표로 닿는 상태(자기 포함)에서만 그대로이고 나머지는 전이 예외다")
    void idempotentStatusesMatchTransitionTable() {
        Set<OrderStatus> shippedOrLater = reachableFrom(OrderStatus.SHIPPING);
        Set<OrderStatus> deliveredOrLater = reachableFrom(OrderStatus.DELIVERED);

        for (OrderStatus status : OrderStatus.values()) {
            Order order = withStatus(status);
            if (status != OrderStatus.PREPARING) {
                assertMovesOrStays(order, () -> order.ship(SHIPPED_AT), shippedOrLater.contains(status));
            }
            if (status != OrderStatus.SHIPPING) {
                assertMovesOrStays(order, () -> order.deliver(DELIVERED_AT), deliveredOrLater.contains(status));
            }
        }
    }

    /** 전이 표로 닿는 상태 — 출발 상태를 포함한다. 표에 새 상태 · 전이가 생기면 멱등 목록도 함께 바뀌어야 한다. */
    private static Set<OrderStatus> reachableFrom(OrderStatus start) {
        Set<OrderStatus> reached = EnumSet.of(start);
        Deque<OrderStatus> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            for (OrderStatus next : queue.poll().nextStatuses()) {
                if (reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reached;
    }

    private static void assertMovesOrStays(Order order, Supplier<Order> call, boolean stays) {
        if (stays) {
            assertThat(call.get()).as("%s 는 멱등이어야 한다", order.status()).isSameAs(order);
        } else {
            assertThatThrownBy(call::get).as("%s 는 거절해야 한다", order.status()).isInstanceOf(OrderStatusTransitionException.class);
        }
    }

    @Test
    @DisplayName("일반 상태 변경으로는 출고 · 배송 완료를 못 한다 — 시각 없이 배송 중 · 배송 완료가 되지 않게 ship · deliver 만 쓴다")
    void changeStatusRejectsShipmentAndDelivery() {
        assertThatThrownBy(() -> withStatus(OrderStatus.PREPARING).changeStatus(OrderStatus.SHIPPING)).isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(() -> withStatus(OrderStatus.SHIPPING).changeStatus(OrderStatus.DELIVERED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThat(withStatus(OrderStatus.PAID).changeStatus(OrderStatus.PREPARING).status()).isEqualTo(OrderStatus.PREPARING);
    }

    @Test
    @DisplayName("연동 이력은 저장된 같은 주문의 상태 변경에서 SERVICE · 아이디 없음 · 사유 코드로 만든다")
    void serviceHistory() {
        Order preparing = withStatus(OrderStatus.PREPARING);
        Order shipped = preparing.ship(SHIPPED_AT);

        OrderStatusHistory history = OrderStatusHistory.changedByService(11L, preparing, shipped, OrderStatusHistory.SHIPMENT_REGISTERED);

        assertThat(history.fromStatus()).isEqualTo(OrderStatus.PREPARING);
        assertThat(history.toStatus()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(history.changedByType()).isEqualTo(StatusChangedBy.SERVICE);
        assertThat(history.changedById()).isNull();
        assertThat(history.reason()).isEqualTo("SHIPMENT_REGISTERED");
        assertThatThrownBy(() -> OrderStatusHistory.changedByService(11L, shipped, shipped, OrderStatusHistory.SHIPMENT_REGISTERED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderStatusHistory.changedByService(11L, preparing, shipped, null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "MAKING", "PREPARING"})
    @DisplayName("requestCancellation — 결제 완료 · 제작 중 · 상품 준비중이면 취소 요청(나머지 값 그대로), 완료는 취소 요청에서 취소 완료다")
    void requestAndCompleteCancellation(OrderStatus status) {
        Order paid = withStatus(status);

        Order requested = paid.requestCancellation();
        Order canceled = requested.completeCancellation();

        assertThat(requested.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(requested).usingRecursiveComparison().ignoringFields("status").isEqualTo(paid);
        assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(requested.requestCancellation()).as("더블클릭 · 재시도").isSameAs(requested);
        assertThat(canceled.requestCancellation()).isSameAs(canceled);
        assertThat(canceled.completeCancellation()).as("확정 반영 재시도").isSameAs(canceled);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PAID", "MAKING", "PREPARING", "CANCEL_REQUESTED", "CANCELED"})
    @DisplayName("결제 전 · 만료 · 출고 이후 주문의 결제 후 취소 요청은 도메인 전이 예외다")
    void requestCancellationRejected(OrderStatus status) {
        assertThatThrownBy(() -> withStatus(status).requestCancellation()).isInstanceOfSatisfying(OrderStatusTransitionException.class, exception -> {
            assertThat(exception.getCurrentStatus()).isEqualTo(status);
            assertThat(exception.getTargetStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        });
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"CANCEL_REQUESTED", "CANCELED"})
    @DisplayName("취소 요청이 아닌 주문의 취소 완료는 도메인 전이 예외다 — 결제 전 취소는 cancelBeforePayment 다")
    void completeCancellationRejected(OrderStatus status) {
        assertThatThrownBy(() -> withStatus(status).completeCancellation()).isInstanceOf(OrderStatusTransitionException.class);
    }

    @Test
    @DisplayName("저장 전 주문은 결제 후 취소 전이를 하지 않는다")
    void cancellationRequiresStoredOrder() {
        Order placed = placed();
        Order unsavedPaid = new Order(placed.id(), placed.memberId(), OrderStatus.PAID, placed.quote(), placed.itemIds(), placed.shippingAddress(),
            placed.stockReservationId(), placed.paymentDueAt(), null, null, null, null);
        Order unsavedRequested = new Order(placed.id(), placed.memberId(), OrderStatus.CANCEL_REQUESTED, placed.quote(), placed.itemIds(),
            placed.shippingAddress(), placed.stockReservationId(), placed.paymentDueAt(), null, null, null, null);

        assertThatThrownBy(unsavedPaid::requestCancellation).isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(unsavedRequested::completeCancellation).isInstanceOf(OrderStatusTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("고객 취소 가능(플래그 판정) — 결제 대기 · 결제 완료 · 상품 준비중, 제작 중은 플래그일 때만. 무인자 판정(결제 대기만)은 그대로다")
    void cancelableByCustomerWithFlag(OrderStatus status) {
        Order order = withStatus(status);
        boolean always = EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.PREPARING).contains(status);

        assertThat(order.isCancelableByCustomer(false)).isEqualTo(always);
        assertThat(order.isCancelableByCustomer(true)).isEqualTo(always || status == OrderStatus.MAKING);
        assertThat(order.isCancelableBeforePayment()).isEqualTo(status == OrderStatus.PENDING_PAYMENT);
        assertThat(status.isCancelableByCustomer(true, false)).as("결제 후 취소 게이트가 닫히면 결제 대기만").isEqualTo(status == OrderStatus.PENDING_PAYMENT);
        assertThat(status.isCancelableByCustomer(false, true)).isEqualTo(order.isCancelableByCustomer(false));
        assertThat(status.isCancelableByCustomer(true, true)).isEqualTo(order.isCancelableByCustomer(true));
        if (order.isCancelableByCustomer(true) && status != OrderStatus.PENDING_PAYMENT) {
            assertThat(order.requestCancellation().status()).as("판정이 열면 전이도 된다").isEqualTo(OrderStatus.CANCEL_REQUESTED);
        }
    }

    @Test
    @DisplayName("결제 후 취소 요청 이력은 고객 · 회원 아이디 · 사유 코드, 환불 완료 이력은 SERVICE · CANCELLATION_REFUNDED 다")
    void cancellationHistories() {
        Order preparing = withStatus(OrderStatus.PREPARING);
        Order requested = preparing.requestCancellation();

        OrderStatusHistory request = OrderStatusHistory.cancellationRequestedByCustomer(11L, preparing, OrderCancelReason.CHANGE_OF_MIND);
        OrderStatusHistory refunded = OrderStatusHistory.cancellationRefunded(12L, requested);

        assertThat(request.fromStatus()).isEqualTo(OrderStatus.PREPARING);
        assertThat(request.toStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(request.changedByType()).isEqualTo(StatusChangedBy.CUSTOMER);
        assertThat(request.changedById()).isEqualTo(preparing.memberId());
        assertThat(request.reason()).isEqualTo("CHANGE_OF_MIND");
        assertThat(refunded.fromStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(refunded.toStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(refunded.changedByType()).isEqualTo(StatusChangedBy.SERVICE);
        assertThat(refunded.changedById()).isNull();
        assertThat(refunded.reason()).isEqualTo(OrderStatusHistory.CANCELLATION_REFUNDED);
        for (OrderStatus status : EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.SHIPPING, OrderStatus.CANCEL_REQUESTED, OrderStatus.CANCELED)) {
            assertThatThrownBy(() -> OrderStatusHistory.cancellationRequestedByCustomer(13L, withStatus(status), OrderCancelReason.OTHER))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> OrderStatusHistory.cancellationRefunded(13L, preparing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderStatusHistory.cancellationRefunded(13L, requested.completeCancellation())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반품 정리 — 정책 결과(ReturnOutcome)만 받아 반품 요청에서 반품 완료 · 배송 완료로만 가고 출고 · 배송 완료 시각을 그대로 둔다. 이미 그 상태면 그대로다 (#17 B1)")
    void settleReturn() {
        Order requested = returnRequested();

        Order returned = requested.settleReturn(ReturnOutcome.RETURNED);
        Order delivered = requested.settleReturn(ReturnOutcome.DELIVERED);

        assertThat(returned.status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered).usingRecursiveComparison().ignoringFields("status").isEqualTo(requested);
        assertThat(delivered.deliveredAt()).as("자동 구매 확정 기준을 덮지 않는다").isEqualTo(DELIVERED_AT);
        assertThat(returned.shippedAt()).isEqualTo(SHIPPED_AT);
        assertThat(requested.settleReturn(ReturnOutcome.STILL_REQUESTED)).isSameAs(requested);
        assertThat(returned.settleReturn(ReturnOutcome.RETURNED)).isSameAs(returned);
        assertThat(delivered.settleReturn(ReturnOutcome.DELIVERED)).isSameAs(delivered);
        assertThatThrownBy(() -> returned.settleReturn(ReturnOutcome.DELIVERED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(() -> delivered.settleReturn(ReturnOutcome.RETURNED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(() -> delivered.settleReturn(ReturnOutcome.STILL_REQUESTED)).as("배송 완료에서 반품 요청으로 되돌리지 않는다")
            .isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(() -> withStatus(OrderStatus.PAID).settleReturn(ReturnOutcome.DELIVERED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(() -> withStatus(OrderStatus.PURCHASE_CONFIRMED).settleReturn(ReturnOutcome.DELIVERED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(() -> requested.settleReturn(null)).isInstanceOf(NullPointerException.class);
        Order unsaved = new Order(requested.id(), requested.memberId(), OrderStatus.RETURN_REQUESTED, requested.quote(), requested.itemIds(),
            requested.shippingAddress(), requested.stockReservationId(), requested.paymentDueAt(), SHIPPED_AT, DELIVERED_AT, null, null);
        assertThatThrownBy(() -> unsaved.settleReturn(ReturnOutcome.DELIVERED)).isInstanceOf(OrderStatusTransitionException.class);
    }

    @Test
    @DisplayName("반품 요청 → 배송 완료는 반품 정리만 한다 — 일반 상태 변경은 여전히 거절하고, 어드민 직접 전이(023)도 아니다")
    void deliveredFromReturnOnlyBySettlement() {
        Order requested = returnRequested();

        assertThat(OrderStatus.RETURN_REQUESTED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
        assertThatThrownBy(() -> requested.changeStatus(OrderStatus.DELIVERED)).isInstanceOf(OrderStatusTransitionException.class);
        assertThat(AdminStatusChangePolicy.decide(requested, OrderStatus.DELIVERED)).isEqualTo(AdminStatusChangePolicy.Decision.NOT_MANUAL);
        assertThat(AdminStatusChangePolicy.decide(requested, OrderStatus.RETURNED)).isEqualTo(AdminStatusChangePolicy.Decision.NOT_MANUAL);
        assertThat(AdminStatusChangePolicy.manualTargets(requested)).isEmpty();
        assertThat(requested.autoConfirmPurchase(DELIVERED_AT.plusSeconds(1))).as("반품 요청 중에는 자동 구매 확정하지 않는다").isSameAs(requested);
    }

    private static Order returnRequested() {
        Order order = placed();
        return new Order(order.id(), order.memberId(), OrderStatus.RETURN_REQUESTED, order.quote(), order.itemIds(), order.shippingAddress(),
            order.stockReservationId(), order.paymentDueAt(), SHIPPED_AT, DELIVERED_AT, Instant.EPOCH, 5L);
    }
}
