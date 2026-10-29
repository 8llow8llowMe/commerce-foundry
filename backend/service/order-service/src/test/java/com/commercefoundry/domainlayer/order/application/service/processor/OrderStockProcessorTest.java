package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
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
        OrderStockProcessor subject = new OrderStockProcessor(malformed, Clock.fixed(NOW, ZoneOffset.UTC));
        when(malformed.reserve(ORDER_ID, ITEMS)).thenReturn(null, new StockReservationQueryResult(1, ORDER_ID + 1, NOW.plusSeconds(60)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> subject.reserve(ORDER_ID, QUOTE)).isInstanceOfSatisfying(OrderException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
        }
    }
}
