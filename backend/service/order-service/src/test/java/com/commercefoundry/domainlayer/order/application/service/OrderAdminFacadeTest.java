package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderStatusChangeResultItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderStatusBulkChangeResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.OrderAdminPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderAdminDetail;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 어드민 주문 Facade (#17) — 버전 충돌의 오류 코드 · 일괄 변경의 건별 결과 · 건마다 남는 감사. */
class OrderAdminFacadeTest {

    private static final OrderStatusChangeCommand PREPARING = new OrderStatusChangeCommand(OrderStatus.PREPARING, null);

    private final OrderAdminProcessor processor = mock(OrderAdminProcessor.class);
    private final OrderAdminAuditProcessor auditProcessor = mock(OrderAdminAuditProcessor.class);
    private final OrderAdminFacade facade = new OrderAdminFacade(processor, auditProcessor, new OrderAdminPresenter());

    /** 버전 충돌 뒤 다시 읽은 주문 — 상태만 본다. */
    private OrderAdminDetail nowIn(long orderId, OrderStatus status) {
        Order order = mock(Order.class);
        when(order.status()).thenReturn(status);
        OrderAdminDetail detail = new OrderAdminDetail(order, List.of(), Set.of());
        when(processor.getDetail(orderId)).thenReturn(detail);
        return detail;
    }

    @Test
    @DisplayName("목록 열람이 끝난 뒤 조건을 그대로 넘겨 한 번 감사한다")
    void searchIsAuditedAfterReading() {
        OrderAdminSearchCommand command = new OrderAdminSearchCommand(null, null, null, 0, 20);
        when(processor.search(command)).thenReturn(Page.empty());

        assertThat(facade.getOrders(command, 7L).contents()).isEmpty();

        var ordered = inOrder(processor, auditProcessor);
        ordered.verify(processor).search(command);
        ordered.verify(auditProcessor).searched(7L, command);
        verifyNoMoreInteractions(auditProcessor);
    }

    @Test
    @DisplayName("상세 응답을 만든 뒤에 열람을 한 번 감사한다")
    void detailIsAuditedAfterPresenting() {
        OrderAdminDetail detail = nowIn(1L, OrderStatus.PAID);
        OrderAdminPresenter presenter = mock(OrderAdminPresenter.class);
        OrderAdminResponse response = OrderAdminResponse.builder().orderId("1").build();
        when(presenter.toResponse(detail)).thenReturn(response);

        assertThat(new OrderAdminFacade(processor, auditProcessor, presenter).getOrder(1L, 7L)).isSameAs(response);

        var ordered = inOrder(processor, presenter, auditProcessor);
        ordered.verify(processor).getDetail(1L);
        ordered.verify(presenter).toResponse(detail);
        ordered.verify(auditProcessor).viewed(7L, 1L);
        verifyNoMoreInteractions(auditProcessor);
    }

    @Test
    @DisplayName("단건 변경이 버전 충돌로 지면 409 ORDER_025 다 — 다시 읽은 상태가 목표와 다를 때")
    void concurrentChangeIsConflict() {
        when(processor.changeStatus(1L, PREPARING, 7L)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        nowIn(1L, OrderStatus.CANCEL_REQUESTED);

        assertThatThrownBy(() -> facade.changeStatus(1L, PREPARING, 7L)).isInstanceOfSatisfying(OrderException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));
        verify(auditProcessor).statusChanged(7L, 1L, false);
        verifyNoMoreInteractions(auditProcessor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("단건 변경 · 그대로 두기가 확정된 뒤 응답 재조회가 실패해도 감사는 SUCCESS 한 번이다")
    void responseReadFailureKeepsSuccessfulAudit(boolean changed) {
        when(processor.changeStatus(1L, PREPARING, 7L)).thenReturn(changed);
        RuntimeException failure = new CannotAcquireLockException("SECRET response row");
        when(processor.getDetail(1L)).thenThrow(failure);

        assertThatThrownBy(() -> facade.changeStatus(1L, PREPARING, 7L)).isSameAs(failure);

        var ordered = inOrder(processor, auditProcessor);
        ordered.verify(processor).changeStatus(1L, PREPARING, 7L);
        ordered.verify(auditProcessor).statusChanged(7L, 1L, true);
        ordered.verify(processor).getDetail(1L);
        verifyNoMoreInteractions(auditProcessor);
    }

    @Test
    @DisplayName("단건 변경 자체가 실패하면 원래 예외를 던지고 FAILURE 한 번만 남긴다")
    void changeFailureIsAuditedOnce() {
        RuntimeException failure = new CannotAcquireLockException("SECRET change row");
        when(processor.changeStatus(1L, PREPARING, 7L)).thenThrow(failure);

        assertThatThrownBy(() -> facade.changeStatus(1L, PREPARING, 7L)).isSameAs(failure);

        verify(auditProcessor).statusChanged(7L, 1L, false);
        verifyNoMoreInteractions(auditProcessor);
        verify(processor, never()).getDetail(anyLong());
    }

    @Test
    @DisplayName("같은 목표 상태로 먼저 바뀐 낙관적 충돌은 그대로 두기 성공이며 재조회 실패로 감사를 뒤집지 않는다")
    void concurrentSameTargetKeepsSuccessfulAudit() {
        when(processor.changeStatus(1L, PREPARING, 7L)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        OrderAdminDetail current = nowIn(1L, OrderStatus.PREPARING);
        RuntimeException failure = new CannotAcquireLockException("SECRET response row");
        when(processor.getDetail(1L)).thenReturn(current).thenThrow(failure);

        assertThatThrownBy(() -> facade.changeStatus(1L, PREPARING, 7L)).isSameAs(failure);

        var ordered = inOrder(processor, auditProcessor);
        ordered.verify(processor).changeStatus(1L, PREPARING, 7L);
        ordered.verify(processor).getDetail(1L);
        ordered.verify(auditProcessor).statusChanged(7L, 1L, true);
        ordered.verify(processor).getDetail(1L);
        verifyNoMoreInteractions(auditProcessor);
    }

    @Test
    @DisplayName("일괄 변경은 주문마다 결과를 요청 순서로 남긴다 — 한 건의 실패가 다른 건을 막지 않고, 예상 못 한 오류는 원인을 싣지 않은 ORDER_026 이다")
    void bulkReportsEachOrder() {
        when(processor.changeStatus(1L, PREPARING, 7L)).thenReturn(true);
        when(processor.changeStatus(2L, PREPARING, 7L)).thenReturn(false);
        when(processor.changeStatus(3L, PREPARING, 7L)).thenThrow(new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, "결제 대기"));
        when(processor.changeStatus(4L, PREPARING, 7L)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 4L));
        nowIn(4L, OrderStatus.CANCEL_REQUESTED);
        when(processor.changeStatus(5L, PREPARING, 7L)).thenThrow(new CannotAcquireLockException("SECRET row detail"));
        when(processor.changeStatus(6L, PREPARING, 7L)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 6L));
        nowIn(6L, OrderStatus.PREPARING);

        OrderStatusBulkChangeResponse response = facade.changeStatuses(List.of(1L, 2L, 3L, 4L, 5L, 6L), PREPARING, 7L);

        assertThat(response.results()).extracting(OrderStatusChangeResultItem::orderId, OrderStatusChangeResultItem::result, OrderStatusChangeResultItem::errorCode)
            .containsExactly(
                tuple("1", "CHANGED", null), tuple("2", "UNCHANGED", null), tuple("3", "FAILED", "ORDER_020"),
                tuple("4", "FAILED", "ORDER_025"), tuple("5", "FAILED", "ORDER_026"), tuple("6", "UNCHANGED", null));
        assertThat(response.results().get(4).message()).doesNotContain("SECRET");
        assertThat(response.changedCount()).isEqualTo(1);
        assertThat(response.unchangedCount()).isEqualTo(2);
        assertThat(response.failedCount()).isEqualTo(3);
        // 감사는 건마다 — 그대로 둔 것도 성공으로 남는다
        for (long orderId : List.of(1L, 2L, 6L)) {
            verify(auditProcessor).statusChanged(7L, orderId, true);
        }
        for (long orderId : List.of(3L, 4L, 5L)) {
            verify(auditProcessor).statusChanged(7L, orderId, false);
        }
        verifyNoMoreInteractions(auditProcessor);
    }
}
