package com.commercefoundry.domainlayer.order.application.service.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderHistoryPurchaseConfirmationProcessorTest {
    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final OrderEventPort events = mock(OrderEventPort.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final OrderHistoryProcessor processor = new OrderHistoryProcessor(orders, events, ids);

    @Test
    void deliveredOrderRecordsCustomerHistoryWithoutInventoryEvent() {
        Order before = mock(Order.class);
        Order after = mock(Order.class);
        when(before.isNew()).thenReturn(false);
        when(before.id()).thenReturn(9L);
        when(before.memberId()).thenReturn(7L);
        when(before.status()).thenReturn(OrderStatus.DELIVERED);
        when(before.changeStatus(OrderStatus.PURCHASE_CONFIRMED)).thenReturn(after);
        when(orders.findByIdAndMemberId(9L, 7L)).thenReturn(Optional.of(before));
        when(ids.generateId()).thenReturn(11L);

        assertThat(processor.confirmPurchase(7L, 9L)).isSameAs(after);
        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orders).saveStatusChange(eq(after), history.capture());
        assertThat(history.getValue().changedById()).isEqualTo(7L);
        assertThat(history.getValue().toStatus()).isEqualTo(OrderStatus.PURCHASE_CONFIRMED);
        verifyNoInteractions(events);
    }

    @Test
    void repeatedConfirmationDoesNotWriteAgain() {
        Order order = mock(Order.class);
        when(order.status()).thenReturn(OrderStatus.PURCHASE_CONFIRMED);
        when(orders.findByIdAndMemberId(9L, 7L)).thenReturn(Optional.of(order));
        assertThat(processor.confirmPurchase(7L, 9L)).isSameAs(order);
        verify(orders, never()).saveStatusChange(any(), any());
        verifyNoInteractions(events, ids);
    }

    @Test
    void otherMembersOrderIsNotDisclosed() {
        when(orders.findByIdAndMemberId(9L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> processor.confirmPurchase(7L, 9L)).isInstanceOfSatisfying(OrderException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(OrderErrorCode.NOT_FOUND_ORDER));
    }

    @Test
    void returnRequestedCannotBeConfirmed() {
        Order order = mock(Order.class);
        when(order.status()).thenReturn(OrderStatus.RETURN_REQUESTED);
        when(orders.findByIdAndMemberId(9L, 7L)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> processor.confirmPurchase(7L, 9L)).isInstanceOfSatisfying(OrderException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID));
        verify(orders, never()).saveStatusChange(any(), any());
    }
}
