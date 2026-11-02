package com.commercefoundry.domainlayer.order.application.service;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.PaymentApprovalTarget;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentApprovalProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.OptimisticLockingFailureException;

class OrderPaymentEventFacadeTest {
    private final PaymentApprovalProcessor verify = mock(PaymentApprovalProcessor.class);
    private final OrderPaymentProcessor database = mock(OrderPaymentProcessor.class);
    private final OrderStockProcessor stock = mock(OrderStockProcessor.class);
    private final OrderPaymentEventFacade facade = new OrderPaymentEventFacade(verify, database, stock);
    private final PaymentApprovalCommand command = new PaymentApprovalCommand(UUID.randomUUID(), 10, 20, 30, krw(4000), Instant.now(), "CARD");
    private final VerifiedPayment payment = new VerifiedPayment(10, 20, 30, krw(4000), command.approvedAt(), true, false);
    private final Order order = mock(Order.class);

    @Test
    void inboxSkipsRemoteCalls() {
        when(database.isProcessed(command.eventId())).thenReturn(true);
        facade.approve(command);
        verifyNoInteractions(verify, stock);
    }

    @Test
    void versionConflictRetainsConfirmedIdWhenCanceledWins() {
        when(verify.verify(command)).thenReturn(payment);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(false, order, null),
            new PaymentApprovalTarget(false, order, OrderPayment.Result.REJECTED_ORDER_CANCELED));
        StockConfirmation confirmed = StockConfirmation.confirmed(99);
        when(stock.confirmOrReReserve(order)).thenReturn(confirmed);
        doThrow(new OptimisticLockingFailureException("PRIVATE_SENTINEL")).doNothing().when(database).complete(command, payment, confirmed);
        facade.approve(command);
        verify(stock, times(1)).confirmOrReReserve(order);
        verify(database, times(2)).complete(command, payment, confirmed);
    }

    @ParameterizedTest
    @EnumSource(value = OrderErrorCode.class, names = {"PRODUCT_SERVICE_UNAVAILABLE", "STOCK_RESERVATION_INCONSISTENT"})
    void classifiesStockFailureWithoutCauseOrPrivateText(OrderErrorCode code) {
        when(verify.verify(command)).thenReturn(payment);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(false, order, null));
        when(stock.confirmOrReReserve(order)).thenThrow(new OrderException(code, new IllegalStateException("PRIVATE_SENTINEL")));
        assertThatThrownBy(() -> facade.approve(command))
            .isInstanceOf(code == OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE ? OrderException.class : NonRetryableEventException.class)
            .hasNoCause().hasMessageNotContaining("PRIVATE_SENTINEL");
        verify(database, never()).complete(any(), any(), any());
    }
}
