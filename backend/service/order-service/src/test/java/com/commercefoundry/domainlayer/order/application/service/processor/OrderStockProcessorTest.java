package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.support.FakeStockReservationPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 재고 선점 사유별 코드 — 다시 보내지 않는다(같은 키의 재시도가 같은 주문 아이디로 다시 선점한다) ({@code order-flow-guide.md} §3-4). */
class OrderStockProcessorTest {

    private static final long ORDER_ID = 4611686018427387904L;
    /** 9001 은 일반 줄 2개 + 맞춤 입력 줄 1개 — 선점은 SKU 별 합(3)이다. */
    private static final OrderQuote QUOTE = domesticQuote(quoteLine(9001, 10_000, 2), quoteLine(9002, 5_000, 1),
        quoteLine(9001, 10_000, 1, OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96")))));
    private static final SortedMap<Long, Integer> ITEMS = new TreeMap<>(Map.of(9001L, 3, 9002L, 1));
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    private final FakeStockReservationPort port = new FakeStockReservationPort();
    private final OrderStockProcessor processor = new OrderStockProcessor(port, Clock.fixed(NOW, ZoneOffset.UTC));

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
        "REJECTED, PRODUCT_SERVICE_UNAVAILABLE",
        "ALREADY_RELEASED, PRODUCT_SERVICE_UNAVAILABLE"
    })
    @DisplayName("021 → 016 · 022 → 003 · 026 → 018 · 023 → 017(500) · 장애 → 002 — 어느 것도 다시 보내지 않는다")
    void failures(Failure failure, OrderErrorCode expected) {
        port.failNextReserve(failure);

        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
        assertThat(port.reserveCalls()).hasSize(1);
    }

    @Test
    @DisplayName("재고 부족 · 판매 불가의 문구는 우리 오류 코드의 것이다 — product 메시지(SKU 목록)를 넘기지 않고, 판매 불가는 주문서 줄 이름으로 알린다(SKU 아이디를 싣지 않는다)")
    void customerMessagesAreOurs() {
        port.failNextReserve(Failure.INSUFFICIENT);
        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE)).hasMessage(OrderErrorCode.STOCK_INSUFFICIENT.getMessage());

        port.failNextReserve(Failure.NOT_PURCHASABLE);
        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE)).hasMessage("지금은 구매할 수 없는 상품이 있습니다: 상품9001(옵션9001), 상품9002(옵션9002)");
    }

    @Test
    @DisplayName("만료 시각이 지금이거나 지났으면 결과를 모르는 503 이다 — 결제 기한이 과거인 주문을 만들지 않는다")
    void expiredReservationIsUnusable() {
        port.expiresAt(NOW);

        assertThatThrownBy(() -> processor.reserve(ORDER_ID, QUOTE))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("흉내 낸 product 계약 — 같은 주문 아이디는 같은 선점, 해제 뒤 다시 선점하면 새 선점이다")
    void fakeFollowsContract() {
        StockReservation first = processor.reserve(ORDER_ID, QUOTE);
        assertThat(processor.reserve(ORDER_ID, QUOTE).reservationId()).isEqualTo(first.reservationId());

        port.release(first.reservationId());

        assertThat(processor.reserve(ORDER_ID, QUOTE).reservationId()).isNotEqualTo(first.reservationId());
    }
}
