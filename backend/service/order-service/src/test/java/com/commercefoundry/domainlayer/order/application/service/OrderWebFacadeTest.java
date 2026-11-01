package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.catalog.application.service.processor.CatalogSkuProcessor;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderWebPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderQuoteProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderRequestProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.support.OrderFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderWebFacadeTest {
    @ParameterizedTest
    @EnumSource(OrderSaveException.Reason.class)
    void onlyOrderPrimaryKeyDuplicateCanReplay(OrderSaveException.Reason reason) {
        var catalog = mock(CatalogSkuProcessor.class);
        var quotes = mock(OrderQuoteProcessor.class);
        var requests = mock(OrderRequestProcessor.class);
        var stocks = mock(OrderStockProcessor.class);
        var orders = mock(OrderProcessor.class);
        var presenter = mock(OrderWebPresenter.class);
        var facade = new OrderWebFacade(catalog, quotes, requests, stocks, orders, presenter);
        var command = new OrderCreateCommand(List.of(OrderFixtures.line(101, 1)), OrderFixtures.address("KR"), null);
        UUID key = UUID.randomUUID();
        var owned = OrderRequest.start(1, 2, key, command.fingerprint(), 3, Instant.now(), Duration.ofSeconds(30));
        var checked = OrderRequestClaim.none();
        var acquired = new OrderRequestClaim(OrderRequestClaim.Outcome.ACQUIRED, owned);
        when(requests.check(2, key, command.fingerprint())).thenReturn(checked);
        when(requests.acquire(2, key, command.fingerprint(), checked)).thenReturn(acquired);
        when(quotes.quote(any(), any(), any())).thenReturn(OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)));
        when(orders.create(eq(2L), eq(owned), eq(command), any(), any())).thenThrow(new OrderSaveException(reason, "SafeFailureType"));
        if (reason == OrderSaveException.Reason.ALREADY_SAVED) {
            when(orders.getOwned(2, 3)).thenReturn(mock(Order.class));
            assertThat(facade.create(2, key, command).created()).isFalse();
            verify(orders).getOwned(2, 3);
        } else {
            assertThatThrownBy(() -> facade.create(2, key, command)).isInstanceOfSatisfying(OrderException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_SAVE_FAILED));
            verify(orders, never()).getOwned(anyLong(), anyLong());
        }
        verify(requests).endLeaseQuietly(owned);
        verify(stocks, times(1)).reserve(eq(3L), any());
    }

    @Test
    @DisplayName("주문 생성은 회원의 결제 대기 주문 수량을 상품별로 읽어 견적의 수량 상한에 넘긴다 (#426)")
    void passesPendingQuantitiesToQuote() {
        var catalog = mock(CatalogSkuProcessor.class);
        var quotes = mock(OrderQuoteProcessor.class);
        var requests = mock(OrderRequestProcessor.class);
        var orders = mock(OrderProcessor.class);
        var facade = new OrderWebFacade(catalog, quotes, requests, mock(OrderStockProcessor.class), orders, mock(OrderWebPresenter.class));
        var command = new OrderCreateCommand(List.of(OrderFixtures.line(101, 1)), OrderFixtures.address("KR"), null);
        UUID key = UUID.randomUUID();
        CatalogSku sku = mock(CatalogSku.class);
        when(sku.productId()).thenReturn(7101L);
        when(catalog.findSkus(any())).thenReturn(Map.of(101L, sku));
        when(requests.check(2, key, command.fingerprint())).thenReturn(OrderRequestClaim.none());
        when(orders.pendingQuantitiesByProduct(2, Set.of(7101L))).thenReturn(Map.of(7101L, 3));
        doThrow(new OrderException(OrderErrorCode.QUANTITY_EXCEEDED, "치마(최대 3개, 결제 대기 주문에 3개)"))
            .when(quotes).quote(any(), any(), eq(Map.of(7101L, 3)));

        assertThatThrownBy(() -> facade.create(2, key, command)).isInstanceOfSatisfying(OrderException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.QUANTITY_EXCEEDED));
        verify(requests, never()).acquire(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("새 키가 활성 시도 상한을 넘으면 원격 호출(SKU 조회 · 견적 · 선점) 없이 ORDER_022 다 (#231)")
    void newKeyOverActiveCapIsRejectedBeforeRemoteCalls() {
        var catalog = mock(CatalogSkuProcessor.class);
        var quotes = mock(OrderQuoteProcessor.class);
        var requests = mock(OrderRequestProcessor.class);
        var stocks = mock(OrderStockProcessor.class);
        var orders = mock(OrderProcessor.class);
        var facade = new OrderWebFacade(catalog, quotes, requests, stocks, orders, mock(OrderWebPresenter.class));
        var command = new OrderCreateCommand(List.of(OrderFixtures.line(101, 1)), OrderFixtures.address("KR"), null);
        UUID key = UUID.randomUUID();
        when(requests.check(2, key, command.fingerprint())).thenReturn(OrderRequestClaim.none());
        doThrow(new OrderException(OrderErrorCode.ACTIVE_ORDER_REQUESTS_EXCEEDED)).when(requests).requireCapacity(2, null);

        assertThatThrownBy(() -> facade.create(2, key, command)).isInstanceOfSatisfying(OrderException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ACTIVE_ORDER_REQUESTS_EXCEEDED));
        verifyNoInteractions(catalog, quotes, stocks, orders);
        verify(requests, never()).acquire(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("기록을 잡은 뒤 다시 센 결과가 상한이면 선점하지 않고 lease 를 끝낸다 — 동시 새 키 넘침 차단 (#231)")
    void recheckAfterAcquireStopsBeforeReservation() {
        var catalog = mock(CatalogSkuProcessor.class);
        var quotes = mock(OrderQuoteProcessor.class);
        var requests = mock(OrderRequestProcessor.class);
        var stocks = mock(OrderStockProcessor.class);
        var orders = mock(OrderProcessor.class);
        var facade = new OrderWebFacade(catalog, quotes, requests, stocks, orders, mock(OrderWebPresenter.class));
        var command = new OrderCreateCommand(List.of(OrderFixtures.line(101, 1)), OrderFixtures.address("KR"), null);
        UUID key = UUID.randomUUID();
        var owned = OrderRequest.start(1, 2, key, command.fingerprint(), 3, Instant.now(), Duration.ofSeconds(30));
        when(requests.check(2, key, command.fingerprint())).thenReturn(OrderRequestClaim.none());
        when(requests.acquire(2, key, command.fingerprint(), OrderRequestClaim.none()))
            .thenReturn(new OrderRequestClaim(OrderRequestClaim.Outcome.ACQUIRED, owned));
        when(quotes.quote(any(), any(), any())).thenReturn(OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)));
        doThrow(new OrderException(OrderErrorCode.ACTIVE_ORDER_REQUESTS_EXCEEDED)).when(requests).requireCapacity(2, owned);

        assertThatThrownBy(() -> facade.create(2, key, command)).isInstanceOfSatisfying(OrderException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ACTIVE_ORDER_REQUESTS_EXCEEDED));
        verify(requests).endLeaseQuietly(owned);
        verifyNoInteractions(stocks);
        verify(orders, never()).create(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("인수는 자기 기록을 빼고 세고(창 안 재시도는 통과), 재생은 세지 않는다 (#231)")
    void takeOverExcludesOwnRecordAndReplayIsNotCounted() {
        var catalog = mock(CatalogSkuProcessor.class);
        var quotes = mock(OrderQuoteProcessor.class);
        var requests = mock(OrderRequestProcessor.class);
        var stocks = mock(OrderStockProcessor.class);
        var orders = mock(OrderProcessor.class);
        var facade = new OrderWebFacade(catalog, quotes, requests, stocks, orders, mock(OrderWebPresenter.class));
        var command = new OrderCreateCommand(List.of(OrderFixtures.line(101, 1)), OrderFixtures.address("KR"), null);
        UUID key = UUID.randomUUID();
        var pending = OrderRequest.start(1, 2, key, command.fingerprint(), 3, Instant.now(), Duration.ofSeconds(30));
        var takeOver = new OrderRequestClaim(OrderRequestClaim.Outcome.TAKE_OVER, pending);
        when(requests.check(2, key, command.fingerprint())).thenReturn(takeOver);
        when(requests.acquire(2, key, command.fingerprint(), takeOver)).thenReturn(new OrderRequestClaim(OrderRequestClaim.Outcome.ACQUIRED, pending));
        when(quotes.quote(any(), any(), any())).thenReturn(OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)));
        when(orders.create(eq(2L), eq(pending), eq(command), any(), any())).thenReturn(mock(Order.class));

        assertThat(facade.create(2, key, command).created()).isTrue();
        verify(requests, times(2)).requireCapacity(2, pending);

        var replayKey = UUID.randomUUID();
        var completed = OrderRequest.start(4, 2, replayKey, command.fingerprint(), 5, Instant.now(), Duration.ofSeconds(30)).complete();
        when(requests.check(2, replayKey, command.fingerprint())).thenReturn(new OrderRequestClaim(OrderRequestClaim.Outcome.REPLAY, completed));
        when(orders.getOwned(2, 5)).thenReturn(mock(Order.class));

        assertThat(facade.create(2, replayKey, command).created()).isFalse();
        verify(requests, never()).requireCapacity(2, completed);
        verify(requests, never()).requireCapacity(2, null);
    }
}
