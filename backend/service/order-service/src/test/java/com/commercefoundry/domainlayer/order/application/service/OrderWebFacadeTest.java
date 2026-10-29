package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.catalog.application.service.processor.CatalogSkuProcessor;
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
import java.util.UUID;
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
        when(quotes.quote(any(), any())).thenReturn(OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)));
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
}
