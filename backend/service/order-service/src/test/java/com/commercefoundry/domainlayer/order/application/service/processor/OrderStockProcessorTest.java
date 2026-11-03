package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.support.FakeStockReservationPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** 재고 선점 사유별 코드 — 다시 보내지 않는다(같은 키의 재시도가 같은 주문 아이디로 다시 선점한다) ({@code order-flow-guide.md} §3-4). */
class OrderStockProcessorTest {

    private static final long ORDER_ID = 4611686018427387904L;
    /** 9001 은 일반 줄 2개 + 맞춤 입력 줄 1개 — 선점은 SKU 별 합(3)이다. */
    private static final OrderQuote QUOTE = domesticQuote(quoteLine(9001, 10_000, 2), quoteLine(9002, 5_000, 1),
        quoteLine(9001, 10_000, 1, OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96")))));
    private static final SortedMap<Long, Integer> ITEMS = new TreeMap<>(Map.of(9001L, 3, 9002L, 1));
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    private final FakeStockReservationPort port = new FakeStockReservationPort();
    private final StockRestorationPort restoration = mock(StockRestorationPort.class);
    private final OrderStockProcessor processor = new OrderStockProcessor(port, restoration, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("선점 결과를 받는다 — 결제 기한이 될 만료 시각이 지금보다 뒤다")
    void reserves() {
        StockReservation reservation = processor.reserve(ORDER_ID, QUOTE);

        assertThat(reservation.orderId()).isEqualTo(ORDER_ID);
        assertThat(reservation.expiresAt()).isEqualTo(FakeStockReservationPort.EXPIRES_AT);
        assertThat(port.reserveCalls()).singleElement().isEqualTo(ITEMS);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "INSUFFICIENT, STOCK_INSUFFICIENT",
        "NOT_PURCHASABLE, ITEM_NOT_PURCHASABLE",
        "CONFLICT, IDEMPOTENCY_IN_PROGRESS",
        "ITEMS_MISMATCH, STOCK_RESERVATION_INCONSISTENT",
        "UNKNOWN, PRODUCT_SERVICE_UNAVAILABLE",
        "REJECTED, PRODUCT_SERVICE_UNAVAILABLE"
    })
    @DisplayName("021 → 016 · 022 → 003 · 026 → 018 · 023 → 017(500) · 장애 → 002 — 어느 것도 다시 보내지 않는다")
    void failures(Failure failure, OrderErrorCode expected) {
        port.failNextReserve(failure);

        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
        assertThat(port.reserveCalls()).hasSize(1);
    }

    @Test
    @DisplayName("재고 부족 · 판매 불가는 우리 문구를 쓰며 숨겨진 SKU 의 이름도 노출하지 않는다")
    void customerMessagesAreOurs() {
        port.failNextReserve(Failure.INSUFFICIENT);
        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE)).hasMessage(OrderErrorCode.STOCK_INSUFFICIENT.getMessage());

        port.failNextReserve(Failure.NOT_PURCHASABLE);
        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE))
            .hasMessage(OrderErrorCode.ITEM_NOT_PURCHASABLE.getMessage().formatted("판매하지 않는 상품"));
    }

    @Test
    @DisplayName("만료 시각이 지금이거나 지났으면 결과를 모르는 503 이다 — 결제 기한이 과거인 주문을 만들지 않는다")
    void expiredReservationIsUnusable() {
        port.expiresAt(NOW);

        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
    }


    @Test
    void aggregatesInSkuOrderRegardlessOfQuoteOrder() {
        OrderQuote quote = domesticQuote(quoteLine(9002, 5000, 1), quoteLine(9001, 10000, 1), quoteLine(9001, 10000, 2));
        assertThat(OrderStockProcessor.quantitiesBySkuId(quote)).containsExactlyEntriesOf(ITEMS);
    }

    @Test
    void rejectsInvalidIdsBeforeCallingRemote() {
        assertThatThrownBy(() -> processor.reserve(0, QUOTE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.reserve(ORDER_ID, domesticQuote(quoteLine(0, 100, 1))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(port.reserveCalls()).isEmpty();
    }

    @Test
    void nullOrOtherOrdersResultIsUnavailable() {
        StockReservationPort malformed = mock(StockReservationPort.class);
        OrderStockProcessor subject = new OrderStockProcessor(malformed, restoration, Clock.fixed(NOW, ZoneOffset.UTC));
        when(malformed.reserve(ORDER_ID, ITEMS)).thenReturn(null, new StockReservationQueryResult(1, ORDER_ID + 1, NOW.plusSeconds(60)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> subject.reserve(ORDER_ID, QUOTE)).isInstanceOfSatisfying(OrderException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
        }
    }

    // --- 결제 완료 반영 전 확정 · 재선점 (#308, order-flow-guide.md §3-4) ---

    private static final long RESERVATION_ID = 770_001L;

    private static Order saved(OrderStatus status) {
        AtomicLong ids = new AtomicLong(10);
        Order order = Order.place(ORDER_ID, 7L, QUOTE, ids::incrementAndGet, address("KR"), RESERVATION_ID, NOW);
        return new Order(order.id(), order.memberId(), status, order.quote(), order.itemIds(), order.shippingAddress(),
            order.stockReservationId(), order.paymentDueAt(), null, null, Instant.EPOCH, 3L);
    }

    @Test
    @DisplayName("확정 200 · CONFIRMED — 주문의 선점 그대로다. 재선점하지 않는다. 재전달에서 다시 불러도 같은 결과다(확정 멱등)")
    void confirmsOwnReservation() {
        port.existing(ORDER_ID, RESERVATION_ID);

        StockConfirmation first = processor.confirmOrReReserve(saved(OrderStatus.PENDING_PAYMENT));
        StockConfirmation again = processor.confirmOrReReserve(saved(OrderStatus.PENDING_PAYMENT));

        assertThat(first).isEqualTo(StockConfirmation.confirmed(RESERVATION_ID)).isEqualTo(again);
        assertThat(first.isConfirmed()).isTrue();
        assertThat(port.confirmCalls()).containsExactly(RESERVATION_ID, RESERVATION_ID);
        assertThat(port.reserveCalls()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "EXPIRED"})
    @DisplayName("해제 · 만료(025) — 같은 주문 아이디 · 항목 스냅숏의 SKU 별 합으로 재선점하고 곧바로 새 선점을 확정한다. 재전달은 확정된 같은 선점으로 수렴한다")
    void reReservesAndConfirmsWhenReleased(OrderStatus status) {
        port.existing(ORDER_ID, RESERVATION_ID).expire(RESERVATION_ID);

        StockConfirmation result = processor.confirmOrReReserve(saved(status));

        assertThat(result.outcome()).isEqualTo(StockConfirmation.Outcome.RE_RESERVED_AND_CONFIRMED);
        assertThat(result.reservationId()).isNotEqualTo(RESERVATION_ID);
        assertThat(port.isConfirmed(result.reservationId())).isTrue();
        assertThat(port.reserveCalls()).singleElement().isEqualTo(ITEMS);
        assertThat(port.confirmCalls()).containsExactly(RESERVATION_ID, result.reservationId());

        // 주문 반영 전에 끊긴 뒤 재전달 — 주문은 아직 옛 선점 아이디라 다시 025 → 재선점이 확정된 같은 선점을 200 으로 받는다
        assertThat(processor.confirmOrReReserve(saved(status))).isEqualTo(result);
        assertThat(port.reserveCalls()).hasSize(2);
    }

    @Test
    @DisplayName("선점 없음(003) — 재선점 신호다")
    void reReservesWhenReservationMissing() {
        StockConfirmation result = processor.confirmOrReReserve(saved(OrderStatus.EXPIRED));

        assertThat(result.outcome()).isEqualTo(StockConfirmation.Outcome.RE_RESERVED_AND_CONFIRMED);
        assertThat(port.confirmCalls()).containsExactly(RESERVATION_ID, result.reservationId());
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"INSUFFICIENT", "NOT_PURCHASABLE"})
    @DisplayName("재선점 021 · 022 — 예외가 아니라 STOCK_UNAVAILABLE 결과다(확정하지 않는다)")
    void stockUnavailableIsResult(Failure failure) {
        port.existing(ORDER_ID, RESERVATION_ID).expire(RESERVATION_ID).failNextReserve(failure);

        StockConfirmation result = processor.confirmOrReReserve(saved(OrderStatus.EXPIRED));

        assertThat(result).isEqualTo(StockConfirmation.stockUnavailable());
        assertThat(result.isConfirmed()).isFalse();
        assertThat(port.confirmCalls()).containsExactly(RESERVATION_ID);
    }

    @ParameterizedTest(name = "확정 {0} → {1}")
    @CsvSource({
        "CONFLICT, PRODUCT_SERVICE_UNAVAILABLE",
        "UNKNOWN, PRODUCT_SERVICE_UNAVAILABLE",
        "REJECTED, PRODUCT_SERVICE_UNAVAILABLE"
    })
    @DisplayName("확정 026 · 5xx · 타임아웃 · 서킷 · 모르는 4xx — 재선점 없이 503 예외(재전달로 다시 부른다)")
    void confirmFailures(Failure failure, OrderErrorCode expected) {
        port.existing(ORDER_ID, RESERVATION_ID).failNextConfirm(failure);

        assertThatThrownBy(() -> processor.confirmOrReReserve(saved(OrderStatus.PENDING_PAYMENT)))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
        assertThat(port.reserveCalls()).isEmpty();
    }

    @ParameterizedTest(name = "재선점 {0} → {1}")
    @CsvSource({
        "CONFLICT, PRODUCT_SERVICE_UNAVAILABLE",
        "UNKNOWN, PRODUCT_SERVICE_UNAVAILABLE",
        "ITEMS_MISMATCH, STOCK_RESERVATION_INCONSISTENT"
    })
    @DisplayName("재선점 026 · 장애는 503(재시도), 023 항목 불일치는 500(재시도해도 같다)")
    void reReserveFailures(Failure failure, OrderErrorCode expected) {
        port.existing(ORDER_ID, RESERVATION_ID).expire(RESERVATION_ID).failNextReserve(failure);

        assertThatThrownBy(() -> processor.confirmOrReReserve(saved(OrderStatus.EXPIRED)))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("재선점 직후 확정이 다시 해제 · 실패하면 503 이다 — 재전달이 처음부터 다시 한다")
    void confirmAfterReReserveFailureIsRetryable() {
        port.existing(ORDER_ID, RESERVATION_ID).failNextConfirm(Failure.RELEASED, Failure.RELEASED);

        assertThatThrownBy(() -> processor.confirmOrReReserve(saved(OrderStatus.EXPIRED)))
            .isInstanceOfSatisfying(OrderException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
        assertThat(port.reserveCalls()).hasSize(1);
    }

    @Test
    @DisplayName("확정된 선점이 다른 주문의 것이면 500 STOCK_RESERVATION_INCONSISTENT 다(재시도해도 같다)")
    void confirmedReservationOfAnotherOrderIsInconsistent() {
        StockReservationPort malformed = mock(StockReservationPort.class);
        OrderStockProcessor subject = new OrderStockProcessor(malformed, restoration, Clock.fixed(NOW, ZoneOffset.UTC));
        when(malformed.confirm(RESERVATION_ID)).thenReturn(new StockConfirmationQueryResult(RESERVATION_ID, ORDER_ID + 1));

        assertThatThrownBy(() -> subject.confirmOrReReserve(saved(OrderStatus.PENDING_PAYMENT)))
            .isInstanceOfSatisfying(OrderException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PENDING_PAYMENT", "EXPIRED"})
    @DisplayName("결제 대기 · 만료가 아닌 주문(취소 등)은 확정 · 재선점을 부르지 않는다")
    void onlyPendingOrExpiredOrders(OrderStatus status) {
        assertThatThrownBy(() -> processor.confirmOrReReserve(saved(status))).isInstanceOf(IllegalArgumentException.class);
        assertThat(port.confirmCalls()).isEmpty();
        assertThat(port.reserveCalls()).isEmpty();
    }

    // --- 결제 반영 복구창: 재선점 증거 · 해제 프로브 · 결제 후 복원 (#308 PR-C1, order-flow-guide.md §3-6) ---

    @Test
    @DisplayName("재선점이 새 선점 아이디를 주면 확정 전에 증거 콜백을 부른다 — 첫 확정(주문의 선점)은 콜백 없이 확정한다")
    void reReservationEvidenceIsRecordedBeforeConfirm() {
        port.existing(ORDER_ID, RESERVATION_ID).expire(RESERVATION_ID);
        List<String> sequence = new ArrayList<>();

        StockConfirmation result = processor.confirmOrReReserve(saved(OrderStatus.EXPIRED),
            reReservedId -> sequence.add("evidence:" + reReservedId + ":confirms=" + port.confirmCalls().size()));

        assertThat(sequence).containsExactly("evidence:" + result.reservationId() + ":confirms=1");
        assertThat(port.isConfirmed(result.reservationId())).isTrue();

        port.existing(ORDER_ID + 1, RESERVATION_ID + 1);
        List<Long> none = new ArrayList<>();
        Order other = saved(OrderStatus.PENDING_PAYMENT);
        processor.confirmOrReReserve(new Order(ORDER_ID + 1, other.memberId(), other.status(), other.quote(), other.itemIds(), other.shippingAddress(),
            RESERVATION_ID + 1, other.paymentDueAt(), null, null, Instant.EPOCH, 3L), none::add);
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("증거 기록이 실패하면 새 선점을 확정하지 않고 그 예외가 그대로 나간다(재전달로 다시 한다)")
    void evidenceFailureSkipsConfirm() {
        port.existing(ORDER_ID, RESERVATION_ID).expire(RESERVATION_ID);

        assertThatThrownBy(() -> processor.confirmOrReReserve(saved(OrderStatus.EXPIRED), reReservedId -> {
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(port.confirmCalls()).containsExactly(RESERVATION_ID);
    }

    @Test
    @DisplayName("프로브 — RESERVED 는 풀고(200) 확정된 선점(024)을 돌려준다. 없는 선점(003)은 건너뛴다. 같은 아이디는 한 번만 부른다")
    void probeFindsConfirmed() {
        port.existing(ORDER_ID, RESERVATION_ID).confirm(RESERVATION_ID);

        assertThat(processor.findConfirmedByReleaseProbe(ORDER_ID, List.of(999L, RESERVATION_ID, RESERVATION_ID))).isEqualTo(OptionalLong.of(RESERVATION_ID));
        assertThat(port.releaseCalls()).containsExactly(999L, RESERVATION_ID);
    }

    @Test
    @DisplayName("프로브 — 확정 전 취소(RESERVED · 이미 RELEASED)는 확정 선점 없음이다. 풀린 선점은 그대로 풀린 상태다")
    void probeWithoutConfirmedReleases() {
        port.existing(ORDER_ID, RESERVATION_ID);

        assertThat(processor.findConfirmedByReleaseProbe(ORDER_ID, List.of(RESERVATION_ID))).isEmpty();
        assertThat(processor.findConfirmedByReleaseProbe(ORDER_ID, List.of(RESERVATION_ID))).isEmpty();
        assertThatThrownBy(() -> port.confirm(RESERVATION_ID)).isInstanceOfSatisfying(StockReservationException.class,
            exception -> assertThat(exception.getFailure()).isEqualTo(Failure.RELEASED));
    }

    @ParameterizedTest(name = "프로브 {0} → {1}")
    @CsvSource({
        "CONFLICT, PRODUCT_SERVICE_UNAVAILABLE",
        "UNKNOWN, PRODUCT_SERVICE_UNAVAILABLE",
        "REJECTED, PRODUCT_SERVICE_UNAVAILABLE"
    })
    @DisplayName("프로브 026 · 031 · 5xx · 타임아웃 · 모르는 4xx — 503(재전달로 다시 한다)")
    void probeFailures(Failure failure, OrderErrorCode expected) {
        port.existing(ORDER_ID, RESERVATION_ID).failNextRelease(failure);

        assertThatThrownBy(() -> processor.findConfirmedByReleaseProbe(ORDER_ID, List.of(RESERVATION_ID)))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("프로브 — 확정 선점이 둘이면 500 STOCK_RESERVATION_INCONSISTENT 다")
    void twoConfirmedIsInconsistent() {
        port.failNextRelease(Failure.CONFIRMED, Failure.CONFIRMED);

        assertThatThrownBy(() -> processor.findConfirmedByReleaseProbe(ORDER_ID, List.of(1L, 2L)))
            .isInstanceOfSatisfying(OrderException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT));
    }

    @Test
    @DisplayName("복원 — 클레임 · 확정 선점 · 주문 스냅숏의 SKU 별 수량 합으로 부른다")
    void restoresWithSkuTotals() {
        processor.restoreCanceledPayment(ORDER_ID, 99L, 555L, RESERVATION_ID, QUOTE);

        verify(restoration).restoreCancellation(555L, RESERVATION_ID, ITEMS);
    }

    @Test
    @DisplayName("복원 028(다른 경로가 이미 복원) — WARN 후 복원된 것으로 끝낸다")
    void quantityExceededClosesRestoration() {
        doThrow(new StockReservationException(Failure.QUANTITY_EXCEEDED)).when(restoration).restoreCancellation(anyLong(), anyLong(), any());

        processor.restoreCanceledPayment(ORDER_ID, 99L, 555L, RESERVATION_ID, QUOTE);
    }

    @ParameterizedTest(name = "복원 {0} → {1}")
    @CsvSource({
        "NOT_CONFIRMED, STOCK_RESERVATION_INCONSISTENT",
        "RESTORATION_MISMATCH, STOCK_RESERVATION_INCONSISTENT",
        "NOT_FOUND, STOCK_RESERVATION_INCONSISTENT",
        "CONFLICT, PRODUCT_SERVICE_UNAVAILABLE",
        "UNKNOWN, PRODUCT_SERVICE_UNAVAILABLE",
        "REJECTED, PRODUCT_SERVICE_UNAVAILABLE"
    })
    @DisplayName("복원 027 · 029 · 003 은 500(재시도해도 같다 — DLT), 026 · 030 · 031 · 장애는 503(재시도)")
    void restorationFailures(Failure failure, OrderErrorCode expected) {
        doThrow(new StockReservationException(failure)).when(restoration).restoreCancellation(anyLong(), anyLong(), any());

        assertThatThrownBy(() -> processor.restoreCanceledPayment(ORDER_ID, 99L, 555L, RESERVATION_ID, QUOTE))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
