package com.commercefoundry.domainlayer.order.application.service;

import static com.commercefoundry.domainlayer.order.support.OrderCreateFixtures.KEY;
import static com.commercefoundry.domainlayer.order.support.OrderCreateFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.order.support.OrderCreateFixtures.create;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.KR;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.US;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.line;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.onSale;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.withoutShippingInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.catalog.application.service.processor.CatalogSkuProcessor;
import com.commercefoundry.domainlayer.catalog.support.FakeSkuQueryPort;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderQuoteResponse;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderQuoteWebPresenter;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderWebPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.in.OrderWebUseCase.Result;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderQuoteProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderRequestProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.support.FakeShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.support.FakeStockReservationPort;
import com.commercefoundry.domainlayer.order.support.InMemoryOrderRepository;
import com.commercefoundry.domainlayer.order.support.InMemoryOrderRequestRepository;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Facade 부터 Presenter 까지 실제 Processor 로 돌리고 바깥(DB · product-service · delivery-service)만 가짜로 둔다 — #13 완료 기준
 * (미리보기 · 생성 금액 일치, 같은 멱등 키 1건, 재고 부족 시 주문 0 · 해제 없음, 저장 실패에도 해제 없음).
 */
class OrderWebFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final OrderCreateCommand ORDER = create(List.of(line(9001, 1), line(9002, 2)));

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 3);

    private FakeSkuQueryPort skuQueryPort;
    private FakeShippingFeeQueryPort shippingFeePort;
    private FakeStockReservationPort stockPort;
    private InMemoryOrderRepository orderRepository;
    private InMemoryOrderRequestRepository requestRepository;
    private OrderQuoteWebFacade quoteFacade;
    private OrderWebFacade facade;

    @BeforeEach
    void setUp() {
        skuQueryPort = new FakeSkuQueryPort();
        shippingFeePort = new FakeShippingFeeQueryPort();
        stockPort = new FakeStockReservationPort();
        orderRepository = new InMemoryOrderRepository();
        requestRepository = new InMemoryOrderRequestRepository();
        CatalogSkuProcessor catalog = new CatalogSkuProcessor(skuQueryPort);
        OrderQuoteProcessor quoteProcessor = new OrderQuoteProcessor(shippingFeePort);
        OrderQuoteWebPresenter quotePresenter = new OrderQuoteWebPresenter();
        quoteFacade = new OrderQuoteWebFacade(catalog, quoteProcessor, quotePresenter);
        facade = new OrderWebFacade(catalog, quoteProcessor,
            new OrderRequestProcessor(requestRepository, ids, new OrderProperties(new OrderProperties.Creation(Duration.ofSeconds(60))), clock),
            new OrderStockProcessor(stockPort, clock), new RollingBackOrderProcessor(), new OrderWebPresenter(quotePresenter));
        skuQueryPort.put(onSale(9001, 39_000)).put(onSale(9002, 4_000));
    }

    private OrderRequest request(UUID key) {
        return requestRepository.findByMemberIdAndIdempotencyKey(MEMBER_ID, key).orElseThrow();
    }

    @Test
    @DisplayName("미리보기와 생성의 항목 · 금액 · 배송비가 같다 — 같은 계산 코드, 두 견적 질의(국가 · 우편번호 · 무게 · 기준 금액)도 같다")
    void quoteAndCreateProduceSameAmounts() {
        OrderQuoteResponse quote = quoteFacade.quote(ORDER.toQuoteCommand());
        Result created = facade.create(MEMBER_ID, KEY, ORDER);

        assertThat(created.created()).isTrue();
        assertThat(created.order().amounts()).isEqualTo(quote.amounts());
        assertThat(created.order().items()).isEqualTo(quote.items());
        assertThat(created.order().shipping()).isEqualTo(quote.shipping());
        assertThat(quote.amounts().itemsAmount()).isEqualTo(MoneyItem.of(47_000, "KRW"));
        assertThat(quote.amounts().totalAmount()).isEqualTo(MoneyItem.of(50_000, "KRW"));
        assertThat(shippingFeePort.quoteCalls()).hasSize(2);
        assertThat(shippingFeePort.quoteCalls().get(1)).isEqualTo(shippingFeePort.quoteCalls().get(0));
    }

    @Test
    @DisplayName("생성 — 201, 문자열 아이디 = 기록의 주문 아이디, 결제 대기, 결제 기한 = 선점 만료, 배송지 스냅숏, 상태 이력 하나, 기록 완료")
    void createShape() {
        Result created = facade.create(MEMBER_ID, KEY, ORDER);

        assertThat(created.created()).isTrue();
        assertThat(created.order().orderId()).isEqualTo(String.valueOf(request(KEY).orderId()));
        assertThat(created.order().status().code()).isEqualTo("PENDING_PAYMENT");
        assertThat(created.order().paymentDueAt()).isEqualTo(FakeStockReservationPort.EXPIRES_AT);
        assertThat(created.order().shippingAddress().recipientPhone()).isEqualTo("010-0000-0001");
        assertThat(created.order().createdAt()).isEqualTo(InMemoryOrderRepository.CREATED_AT);
        assertThat(stockPort.reserveCalls()).singleElement().satisfies(call -> assertThat(call).containsExactly(Map.entry(9001L, 1), Map.entry(9002L, 2)));
        assertThat(orderRepository.histories()).singleElement().satisfies(history -> {
            assertThat(history.fromStatus()).isNull();
            assertThat(history.toStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(history.changedById()).isEqualTo(MEMBER_ID);
        });
        assertThat(request(KEY).status()).isEqualTo(OrderRequest.Status.COMPLETED);
    }

    @Test
    @DisplayName("expectedTotalAmount 가 다르면 재고를 잡기 전에 409 ORDER_010 — 기록도 남기지 않는다. 같으면 받는다")
    void expectedTotalAmount() {
        assertCode(() -> facade.create(MEMBER_ID, KEY, create(ORDER.items(), KR, krw(49_999))), OrderErrorCode.ORDER_AMOUNT_CHANGED);
        assertThat(stockPort.reserveCalls()).isEmpty();
        assertThat(requestRepository.all()).isEmpty();

        assertThat(facade.create(MEMBER_ID, KEY, create(ORDER.items(), KR, krw(50_000))).created()).isTrue();
    }

    @Nested
    @DisplayName("멱등")
    class Idempotency {

        @Test
        @DisplayName("같은 키 두 번 — 주문 1건, 두 번째는 200(created=false)으로 같은 주문, 원격(상품 · 배송비 · 재고)을 부르지 않는다")
        void sameKeyTwice() {
            Result first = facade.create(MEMBER_ID, KEY, ORDER);
            int skuCalls = skuQueryPort.calls().size();

            Result second = facade.create(MEMBER_ID, KEY, ORDER);

            assertThat(second.created()).isFalse();
            assertThat(second.order()).isEqualTo(first.order());
            assertThat(orderRepository.all()).hasSize(1);
            assertThat(skuQueryPort.calls()).hasSize(skuCalls);
            assertThat(shippingFeePort.quoteCalls()).hasSize(1);
            assertThat(stockPort.reserveCalls()).hasSize(1);
        }

        @Test
        @DisplayName("같은 키 · 다른 본문은 409 ORDER_019 — 주문은 그대로 하나")
        void differentBody() {
            facade.create(MEMBER_ID, KEY, ORDER);

            assertCode(() -> facade.create(MEMBER_ID, KEY, create(List.of(line(9001, 2)))), OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
            assertThat(orderRepository.all()).hasSize(1);
        }

        @Test
        @DisplayName("lease 안의 처리 중인 같은 키는 409 ORDER_018 — 원격을 부르지 않는다")
        void inProgress() {
            requestRepository.givenSaved(new OrderRequest(1L, MEMBER_ID, KEY, ORDER.fingerprint(), 777L, OrderRequest.Status.PROCESSING, NOW.plusSeconds(30), 0L));

            assertCode(() -> facade.create(MEMBER_ID, KEY, ORDER), OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
            assertThat(skuQueryPort.calls()).isEmpty();
        }

        @Test
        @DisplayName("lease 가 지난 처리 중 기록은 같은 주문 아이디로 이어받아 주문을 만든다 — 선점도 같은 주문 아이디다")
        void takeOver() {
            requestRepository.givenSaved(new OrderRequest(1L, MEMBER_ID, KEY, ORDER.fingerprint(), 777L, OrderRequest.Status.PROCESSING, NOW.minusSeconds(1), 4L));

            Result created = facade.create(MEMBER_ID, KEY, ORDER);

            assertThat(created.created()).isTrue();
            assertThat(created.order().orderId()).isEqualTo("777");
            assertThat(request(KEY).status()).isEqualTo(OrderRequest.Status.COMPLETED);
        }

        @Test
        @DisplayName("실패 뒤 같은 키로 다시 보내면 lease 가 이미 끝나 바로 이어받는다 — 같은 주문 아이디로 다시 선점한다")
        void retryAfterFailure() {
            stockPort.failNextReserve(Failure.INSUFFICIENT);
            assertCode(() -> facade.create(MEMBER_ID, KEY, ORDER), OrderErrorCode.STOCK_INSUFFICIENT);
            long orderId = request(KEY).orderId();
            assertThat(request(KEY).leaseExpiresAt()).as("처리한 실패는 lease 를 바로 끝낸다").isEqualTo(NOW);

            Result created = facade.create(MEMBER_ID, KEY, ORDER);

            assertThat(created.order().orderId()).isEqualTo(String.valueOf(orderId));
            assertThat(stockPort.reserveCalls()).hasSize(2);
        }

        @Test
        @DisplayName("같은 주문 아이디의 주문을 다른 시도가 먼저 저장했으면 그 주문을 200 으로 돌려준다 — 해제하지 않는다")
        void alreadySavedReplays() {
            Result saved = facade.create(MEMBER_ID, KEY, ORDER);
            UUID otherKey = UUID.fromString("11111111-2222-3333-4444-555555555555");
            requestRepository.givenSaved(new OrderRequest(2L, MEMBER_ID, otherKey, ORDER.fingerprint(), Long.parseLong(saved.order().orderId()),
                OrderRequest.Status.PROCESSING, NOW.minusSeconds(1), 0L));

            Result replayed = facade.create(MEMBER_ID, otherKey, ORDER);

            assertThat(replayed.created()).isFalse();
            assertThat(replayed.order()).isEqualTo(saved.order());
            assertThat(orderRepository.all()).hasSize(1);
            assertThat(stockPort.releaseCalls()).isEmpty();
        }
    }

    @Nested
    @DisplayName("실패 경로 — 보상 해제(release)는 어느 경우에도 하지 않는다")
    class Failures {

        @Test
        @DisplayName("재고 부족 — 409 ORDER_016, 주문 0건, 해제 없음(product 가 선점 전체를 되돌렸다), lease 를 끝낸다")
        void stockInsufficientLeavesNoOrder() {
            stockPort.failNextReserve(Failure.INSUFFICIENT);

            assertCode(() -> facade.create(MEMBER_ID, KEY, ORDER), OrderErrorCode.STOCK_INSUFFICIENT);
            assertThat(orderRepository.all()).isEmpty();
            assertThat(stockPort.releaseCalls()).isEmpty();
            assertThat(request(KEY).leaseExpiresAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("같은 주문의 선점 동시 변경(026) — 409 ORDER_018, 다시 보내지 않는다")
        void reservationConflict() {
            stockPort.failNextReserve(Failure.CONFLICT);

            assertCode(() -> facade.create(MEMBER_ID, KEY, ORDER), OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
            assertThat(stockPort.reserveCalls()).hasSize(1);
        }

        @Test
        @DisplayName("배송비 견적 실패 — 재고를 잡기 전이라 선점 · 기록이 없다")
        void shippingFailureBeforeReservation() {
            shippingFeePort.willRejectQuote(OrderErrorCode.SHIPPING_UNAVAILABLE);

            assertCode(() -> facade.create(MEMBER_ID, KEY, ORDER), OrderErrorCode.SHIPPING_UNAVAILABLE);
            assertThat(stockPort.reserveCalls()).isEmpty();
            assertThat(requestRepository.all()).isEmpty();
        }

        @Test
        @DisplayName("저장 실패(DB 거절) — 500 ORDER_021, 주문 0건, 해제 없음, lease 를 끝내 같은 키의 재시도가 같은 선점으로 이어받는다")
        void saveFailureDoesNotRelease() {
            orderRepository.failNextCreate(new OrderSaveException(OrderSaveException.Reason.REJECTED, "DataIntegrityViolationException"));

            assertCode(() -> facade.create(MEMBER_ID, KEY, ORDER), OrderErrorCode.ORDER_SAVE_FAILED);
            assertThat(orderRepository.all()).isEmpty();
            assertThat(stockPort.releaseCalls()).isEmpty();
            assertThat(request(KEY).status()).isEqualTo(OrderRequest.Status.PROCESSING);
            assertThat(request(KEY).leaseExpiresAt()).isEqualTo(NOW);

            Result retried = facade.create(MEMBER_ID, KEY, ORDER);
            assertThat(retried.created()).isTrue();
            assertThat(stockPort.reserveCalls()).as("같은 주문 아이디라 product 가 기존 선점을 준다").hasSize(2);
            assertThat(orderRepository.all()).singleElement().extracting(Order::stockReservationId).isEqualTo(880_000L);
        }

        @Test
        @DisplayName("저장 직전 다른 시도가 기록을 이어받았으면 409 ORDER_018 — 그쪽이 같은 선점을 쓰므로 풀지 않는다")
        void takenOverBeforeSave() {
            OrderRequest taken = new OrderRequest(1L, MEMBER_ID, KEY, ORDER.fingerprint(), 777L, OrderRequest.Status.PROCESSING, NOW.minusSeconds(1), 4L);
            requestRepository.givenSaved(taken);
            stockPort.onReserve(() -> requestRepository.givenSaved(new OrderRequest(1L, MEMBER_ID, KEY, ORDER.fingerprint(), 777L,
                OrderRequest.Status.PROCESSING, NOW.plusSeconds(60), 9L)));

            assertCode(() -> facade.create(MEMBER_ID, KEY, ORDER), OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
            assertThat(orderRepository.all()).isEmpty();
            assertThat(stockPort.releaseCalls()).isEmpty();
            assertThat(request(KEY).version()).as("다른 시도의 lease 를 건드리지 않는다").isEqualTo(9L);
        }
    }

    @Nested
    @DisplayName("해외 (#80)")
    class International {

        @Test
        @DisplayName("총 무게를 견적에 싣고, 무게 요금 · 국가 그룹을 응답에, 무게 · HS 코드 · 총 무게를 주문에 스냅숏한다")
        void internationalOrder() {
            Result created = facade.create(MEMBER_ID, KEY, create(ORDER.items(), US, null));

            assertThat(shippingFeePort.quoteCalls()).singleElement().extracting(ShippingFeeQuoteQuery::weightGrams).isEqualTo(500 * 1 + 500 * 2);
            assertThat(shippingFeePort.destinationChecks()).as("무게 · HS 가 모두 있으면 확인용 견적을 부르지 않는다").isEmpty();
            assertThat(created.order().shipping().weightFee()).isEqualTo(MoneyItem.of(FakeShippingFeeQueryPort.INTERNATIONAL_WEIGHT_FEE, "KRW"));
            assertThat(created.order().shipping().scope()).isEqualTo("INTERNATIONAL");
            assertThat(created.order().amounts().totalAmount()).isEqualTo(MoneyItem.of(47_000 + 4_500, "KRW"));
            assertThat(orderRepository.all()).singleElement().satisfies(saved -> {
                assertThat(saved.quote().lines()).extracting(OrderQuoteLine::weightGrams).containsExactly(500, 500);
                assertThat(saved.quote().lines()).extracting(OrderQuoteLine::hsCode).containsOnly("6211420000");
                assertThat(saved.quote().shippingFeeQuote().scope()).isEqualTo(ShippingScope.INTERNATIONAL);
                assertThat(saved.quote().shippingFeeQuote().totalWeightGrams()).isEqualTo(1_500);
                assertThat(saved.shippingAddress().postalCode()).isNull();
            });
        }

        @Test
        @DisplayName("무게 · HS 미입력 SKU 가 있으면 배송 가능 국가를 확인한 뒤 409 ORDER_007 — 견적 · 재고 · 기록에 닿지 않는다")
        void missingShippingInfo() {
            skuQueryPort.put(withoutShippingInfo(9002, 4_000));

            assertCode(() -> facade.create(MEMBER_ID, KEY, create(ORDER.items(), US, null)), OrderErrorCode.INTERNATIONAL_SHIPPING_INFO_MISSING);
            assertThat(shippingFeePort.destinationChecks()).hasSize(1);
            assertThat(shippingFeePort.quoteCalls()).isEmpty();
            assertThat(stockPort.reserveCalls()).isEmpty();
            assertThat(requestRepository.all()).isEmpty();
        }
    }

    private static void assertCode(ThrowingCallable call, OrderErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    /**
     * {@code OrderProcessor#create} 의 트랜잭션 롤백을 흉내 낸다 — 실제로는 기록 완료와 주문 저장이 한 트랜잭션이라, 저장이 실패하면 기록 완료도 되돌아간다.
     * 메모리 리포지토리에는 트랜잭션이 없어 실패 때 기록 행을 저장 전으로 돌린다.
     */
    private class RollingBackOrderProcessor extends OrderProcessor {

        RollingBackOrderProcessor() {
            super(orderRepository, requestRepository, ids);
        }

        @Override
        public Order create(long memberId, OrderRequest request, OrderCreateCommand command, OrderQuote quote, StockReservation reservation) {
            Map<String, OrderRequest> before = requestRepository.snapshot();
            try {
                return super.create(memberId, request, command, quote, reservation);
            } catch (RuntimeException exception) {
                requestRepository.restore(before);
                throw exception;
            }
        }
    }
}
