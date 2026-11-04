package com.commercefoundry.domainlayer.order.application.service;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.PaymentApprovalTarget;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.StockRestorationTarget;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentApprovalProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

class OrderPaymentEventFacadeTest {
    private final PaymentApprovalProcessor verify = mock(PaymentApprovalProcessor.class);
    private final OrderPaymentProcessor database = mock(OrderPaymentProcessor.class);
    private final OrderStockProcessor stock = mock(OrderStockProcessor.class);
    private final OrderPaymentEventFacade facade = new OrderPaymentEventFacade(verify, database, stock);
    private final PaymentApprovalCommand command = new PaymentApprovalCommand(UUID.randomUUID(), 10, 20, 30, krw(4000), Instant.now(), "CARD");
    private final VerifiedPayment payment = new VerifiedPayment(10, 20, 30, krw(4000), command.approvedAt(), "DONE");
    private final Order order = mock(Order.class);
    private final OrderQuote quote = mock(OrderQuote.class);
    private final OrderPayment restorable = OrderPayment.canceledAfterConfirmation(1, 20, 10, 30, krw(4000), command.approvedAt(), 77, command.eventId(), 555);

    @Test
    void inboxSkipsRemoteCalls() {
        when(database.isProcessed(command.eventId())).thenReturn(true);
        facade.approve(command);
        verifyNoInteractions(verify, stock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring", "pessimistic", "timeout"})
    void nowaitConflictHasBoundedFreshTransactionAttemptsAndRetainsConfirmation(String failure) {
        when(verify.verify(command)).thenReturn(payment);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(false, order, null));
        StockConfirmation confirmed = StockConfirmation.confirmed(99);
        when(stock.confirmOrReReserve(eq(order), any(), any())).thenReturn(confirmed);
        RuntimeException exception = switch (failure) {
            case "pessimistic" -> new jakarta.persistence.PessimisticLockException("locked");
            case "timeout" -> new jakarta.persistence.LockTimeoutException("locked");
            default -> new PessimisticLockingFailureException("locked");
        };
        doThrow(exception).when(database).complete(command, payment, confirmed);

        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(IllegalStateException.class).hasMessageContaining("retry exhausted");

        verify(database, times(3)).complete(command, payment, confirmed);
        verify(stock, times(1)).confirmOrReReserve(eq(order), any(), any());
        verify(database, never()).completeRestoration(any(), any(), any());
    }

    @Test
    void versionConflictRetainsConfirmedIdWhenCanceledWins() {
        when(verify.verify(command)).thenReturn(payment);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(false, order, null),
            new PaymentApprovalTarget(false, order, OrderPayment.Result.REJECTED_ORDER_CANCELED));
        StockConfirmation confirmed = StockConfirmation.confirmed(99);
        when(stock.confirmOrReReserve(eq(order), any(), any())).thenReturn(confirmed);
        doThrow(new OptimisticLockingFailureException("PRIVATE_SENTINEL")).doReturn(Optional.empty()).when(database).complete(command, payment, confirmed);
        facade.approve(command);
        verify(stock, times(1)).confirmOrReReserve(eq(order), any(), any());
        verify(stock, never()).findConfirmedByReleaseProbe(anyLong(), any());
        verify(database, times(2)).complete(command, payment, confirmed);
    }

    @ParameterizedTest
    @EnumSource(value = OrderErrorCode.class, names = {"PRODUCT_SERVICE_UNAVAILABLE", "STOCK_RESERVATION_INCONSISTENT"})
    void classifiesStockFailureWithoutCauseOrPrivateText(OrderErrorCode code) {
        when(verify.verify(command)).thenReturn(payment);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(false, order, null));
        when(stock.confirmOrReReserve(eq(order), any(), any())).thenThrow(new OrderException(code, new IllegalStateException("PRIVATE_SENTINEL")));
        assertThatThrownBy(() -> facade.approve(command))
            .isInstanceOf(code == OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE ? OrderException.class : NonRetryableEventException.class)
            .hasNoCause().hasMessageNotContaining("PRIVATE_SENTINEL");
        verify(database, never()).complete(any(), any(), any());
    }

    @Test
    void canceledOrderProbesCandidatesAndRestoresConfirmedBeforeReply() {
        when(verify.verify(command)).thenReturn(payment);
        when(order.id()).thenReturn(20L);
        when(order.quote()).thenReturn(quote);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(false, order, OrderPayment.Result.REJECTED_ORDER_CANCELED));
        when(database.canProbe(payment, order)).thenReturn(true);
        when(database.confirmationCandidates(10, order)).thenReturn(List.of(77L, 66L));
        when(stock.findConfirmedByReleaseProbe(20, List.of(77L, 66L))).thenReturn(OptionalLong.of(77));
        StockRestorationTarget target = new StockRestorationTarget(restorable, order);
        when(database.complete(command, payment, StockConfirmation.confirmed(77))).thenReturn(Optional.of(target));

        facade.approve(command);

        var sequence = inOrder(stock, database);
        sequence.verify(stock).findConfirmedByReleaseProbe(20, List.of(77L, 66L));
        sequence.verify(database).complete(command, payment, StockConfirmation.confirmed(77));
        sequence.verify(stock).restoreCanceledPayment(20, 10, 555, 77, quote);
        sequence.verify(database).completeRestoration(command.eventId(), restorable, payment);
        verify(stock, never()).confirmOrReReserve(any(), any(), any());
    }

    @Test
    void canceledBeforeConfirmationRejectsWithoutRestoration() {
        when(verify.verify(command)).thenReturn(payment);
        when(order.id()).thenReturn(20L);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(false, order, OrderPayment.Result.REJECTED_ORDER_CANCELED));
        when(database.canProbe(payment, order)).thenReturn(true);
        when(database.confirmationCandidates(10, order)).thenReturn(List.of(66L));
        when(stock.findConfirmedByReleaseProbe(20, List.of(66L))).thenReturn(OptionalLong.empty());
        when(database.complete(command, payment, null)).thenReturn(Optional.empty());

        facade.approve(command);

        verify(stock, never()).restoreCanceledPayment(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(database, never()).completeRestoration(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderErrorCode.class, names = {"PRODUCT_SERVICE_UNAVAILABLE", "STOCK_RESERVATION_INCONSISTENT"})
    void restorationFailureLeavesInboxAndReplyForRedelivery(OrderErrorCode code) {
        when(verify.verify(command)).thenReturn(payment);
        when(order.id()).thenReturn(20L);
        when(order.quote()).thenReturn(quote);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(true, null, null));
        StockRestorationTarget target = new StockRestorationTarget(restorable, order);
        when(database.complete(command, payment, null)).thenReturn(Optional.of(target));
        doThrow(new OrderException(code)).when(stock).restoreCanceledPayment(20, 10, 555, 77, quote);

        assertThatThrownBy(() -> facade.approve(command))
            .isInstanceOf(code == OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE ? OrderException.class : NonRetryableEventException.class);
        verify(database, never()).completeRestoration(any(), any(), any());
        verify(stock, never()).findConfirmedByReleaseProbe(anyLong(), any());
    }

    @Test
    void concurrentSameEventFinishingRestorationFirstIsSwallowed() {
        when(verify.verify(command)).thenReturn(payment);
        when(order.id()).thenReturn(20L);
        when(order.quote()).thenReturn(quote);
        when(database.inspect(payment, command.eventId())).thenReturn(new PaymentApprovalTarget(true, null, null));
        StockRestorationTarget target = new StockRestorationTarget(restorable, order);
        when(database.complete(command, payment, null)).thenReturn(Optional.of(target));
        doThrow(new DataIntegrityViolationException("inbox")).when(database).completeRestoration(command.eventId(), restorable, payment);
        when(database.isProcessed(command.eventId())).thenReturn(false, true);

        facade.approve(command);

        verify(database).completeRestoration(command.eventId(), restorable, payment);
    }
}
