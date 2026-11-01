package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderHistoryWebPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 고객 주문 Facade (#16) — 결제 전 취소가 버전 충돌로 졌을 때 다시 읽어 판단한다. */
class OrderHistoryWebFacadeTest {

    private final OrderHistoryProcessor processor = mock(OrderHistoryProcessor.class);
    private final OrderHistoryWebPresenter presenter = mock(OrderHistoryWebPresenter.class);
    private final OrderHistoryWebFacade facade = new OrderHistoryWebFacade(processor, presenter);

    private Order orderIn(OrderStatus status) {
        Order order = mock(Order.class);
        when(order.status()).thenReturn(status);
        return order;
    }

    @Test
    @DisplayName("같은 취소(더블 클릭)가 먼저 커밋해 버전 충돌이면 다시 읽은 취소 완료 주문으로 200 이다")
    void lostToSameCancelIsSuccess() {
        Order canceled = orderIn(OrderStatus.CANCELED);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.cancel(7L, 1L, OrderCancelReason.OTHER)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        when(processor.get(7L, 1L)).thenReturn(canceled);
        when(presenter.toDetailResponse(canceled)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
    }

    @Test
    @DisplayName("결제 완료 · 만료가 먼저 커밋해 버전 충돌이면 지금 상태로 409 ORDER_020 이다")
    void lostToPaymentOrExpiryIsConflict() {
        when(processor.cancel(7L, 1L, OrderCancelReason.OTHER)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        Order paid = orderIn(OrderStatus.PAID);
        when(processor.get(7L, 1L)).thenReturn(paid);

        assertThatThrownBy(() -> facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isInstanceOfSatisfying(OrderException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID));
        verifyNoInteractions(presenter);
    }
}
