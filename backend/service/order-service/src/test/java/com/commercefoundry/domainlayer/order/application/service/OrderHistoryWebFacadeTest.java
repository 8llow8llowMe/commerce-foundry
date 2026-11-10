package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderClaimItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderHistoryWebPresenter;
import com.commercefoundry.domainlayer.order.application.exception.ClaimRequestKeyConflictException;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderClaimHistoryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import jakarta.persistence.LockTimeoutException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/** 고객 주문 Facade (#16) — 결제 전 취소가 버전 충돌로 졌을 때 다시 읽어 판단하고, 결제 후 취소는 접수 → 파이프라인 → 현재 상세로 잇는다. */
class OrderHistoryWebFacadeTest {

    private final OrderHistoryProcessor processor = mock(OrderHistoryProcessor.class);
    private final OrderCancellationProcessor cancellation = mock(OrderCancellationProcessor.class);
    private final OrderCancellationPipelineProcessor pipeline = mock(OrderCancellationPipelineProcessor.class);
    private final OrderHistoryWebPresenter presenter = mock(OrderHistoryWebPresenter.class);
    private final OrderClaimHistoryProcessor claims = mock(OrderClaimHistoryProcessor.class);
    private final OrderHistoryWebFacade facade = new OrderHistoryWebFacade(processor, claims, cancellation, pipeline, presenter);

    @Test
    @DisplayName("내 주문 클레임은 소유를 확인한 Processor 결과를 Presenter 로 옮긴다 (#609)")
    void claimsArePresented() {
        List<Claim> found = List.of(mock(Claim.class));
        List<OrderClaimItem> items = List.of(mock(OrderClaimItem.class));
        when(claims.findClaims(7L, 1L)).thenReturn(found);
        when(presenter.toClaimItems(found)).thenReturn(items);

        assertThat(facade.getClaims(7L, 1L)).isSameAs(items);
        verifyNoInteractions(cancellation, pipeline);
    }

    private Order orderIn(OrderStatus status) {
        Order order = mock(Order.class);
        when(order.status()).thenReturn(status);
        return order;
    }

    @Test
    @DisplayName("같은 취소(더블 클릭)가 먼저 커밋해 버전 충돌이면 다시 읽은 취소 완료 주문으로 200 이다")
    void lostToSameCancelIsSuccess() {
        Order pending = orderIn(OrderStatus.PENDING_PAYMENT);
        Order canceled = orderIn(OrderStatus.CANCELED);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.cancel(7L, 1L, OrderCancelReason.OTHER)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        when(processor.get(7L, 1L)).thenReturn(pending, canceled);
        when(presenter.toDetailResponse(canceled, false)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
        verifyNoInteractions(cancellation, pipeline);
    }

    @Test
    @DisplayName("만료가 먼저 커밋해 버전 충돌이면 지금 상태로 409 ORDER_020 이다")
    void lostToExpiryIsConflict() {
        Order pending = orderIn(OrderStatus.PENDING_PAYMENT);
        when(processor.cancel(7L, 1L, OrderCancelReason.OTHER)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        Order expired = orderIn(OrderStatus.EXPIRED);
        when(processor.get(7L, 1L)).thenReturn(pending, expired);

        assertThatThrownBy(() -> facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isInstanceOfSatisfying(OrderException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID));
        verifyNoInteractions(presenter, cancellation, pipeline);
    }

    @Test
    @DisplayName("결제 전 경로가 버전 충돌 · ORDER_020 으로 졌는데 다시 읽으니 결제 완료면 결제 후 경로로 넘긴다")
    void lostToPaymentFallsThroughToPaidFlow() {
        Order pending = orderIn(OrderStatus.PENDING_PAYMENT);
        Order paid = orderIn(OrderStatus.PAID);
        Order requested = orderIn(OrderStatus.CANCEL_REQUESTED);
        Claim claim = mock(Claim.class);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.cancel(7L, 1L, OrderCancelReason.OTHER))
            .thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L))
            .thenThrow(new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, "결제 완료"));
        when(processor.get(7L, 1L)).thenReturn(pending, paid, requested, pending, paid, requested);
        when(cancellation.request(7L, 1L, OrderCancelReason.OTHER)).thenReturn(new OrderCancellationProcessor.Requested(true, claim));
        when(presenter.toDetailResponse(requested, false)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
        verify(pipeline, times(2)).progress(claim);
    }

    @Test
    @DisplayName("이미 취소 완료면 결제 전 · 결제 후 어느 쪽이든 결제 전 경로가 바꾸지 않고 돌려준다 — 파이프라인을 다시 돌리지 않는다")
    void canceledOrderReplaysThroughExistingPath() {
        Order canceled = orderIn(OrderStatus.CANCELED);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.get(7L, 1L)).thenReturn(canceled);
        when(processor.cancel(7L, 1L, OrderCancelReason.OTHER)).thenReturn(canceled);
        when(presenter.toDetailResponse(canceled, false)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
        verifyNoInteractions(cancellation, pipeline);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "MAKING", "PREPARING", "CANCEL_REQUESTED", "SHIPPING", "EXPIRED"})
    @DisplayName("결제 대기 · 취소 완료가 아니면 결제 후 경로다 — 접수가 새로 만들면 파이프라인을 잇고 마지막에 다시 읽은 주문으로 응답한다")
    void nonPendingGoesToPaidFlow(OrderStatus status) {
        Order before = orderIn(status);
        Order after = orderIn(OrderStatus.CANCELED);
        Claim claim = mock(Claim.class);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.get(7L, 1L)).thenReturn(before, after);
        when(cancellation.request(7L, 1L, OrderCancelReason.ORDER_MISTAKE)).thenReturn(new OrderCancellationProcessor.Requested(true, claim));
        when(presenter.toDetailResponse(after, false)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.ORDER_MISTAKE)).isSameAs(response);
        verify(pipeline).progress(claim);
        verify(processor, never()).cancel(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("접수가 재생(이미 있는 취소 클레임)이면 파이프라인을 다시 돌리지 않고 현재 상세다")
    void replayDoesNotRerunPipeline() {
        Order requested = orderIn(OrderStatus.CANCEL_REQUESTED);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.get(7L, 1L)).thenReturn(requested);
        when(cancellation.request(7L, 1L, OrderCancelReason.OTHER)).thenReturn(new OrderCancellationProcessor.Requested(false, mock(Claim.class)));
        when(presenter.toDetailResponse(requested, false)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
        verifyNoInteractions(pipeline);
    }

    @Test
    @DisplayName("접수 뒤 파이프라인이 실패해도 접수는 커밋됐으므로 현재 상세(취소 요청)로 200 이다 — 재시도 스케줄러가 잇는다")
    void pipelineFailureAfterRequestStillAnswers() {
        Order paid = orderIn(OrderStatus.PAID);
        Order requested = orderIn(OrderStatus.CANCEL_REQUESTED);
        Claim claim = mock(Claim.class);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.get(7L, 1L)).thenReturn(paid, requested);
        when(cancellation.request(7L, 1L, OrderCancelReason.OTHER)).thenReturn(new OrderCancellationProcessor.Requested(true, claim));
        when(pipeline.progress(claim)).thenThrow(new CannotCreateTransactionException("db down"));
        when(presenter.toDetailResponse(requested, false)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
    }

    @Test
    @DisplayName("접수가 잠금 · 버전 · 결정적 키에서 지면 다시 읽어 같은 취소가 접수돼 있으면 재생, 아니면 409 ORDER_025 다")
    void requestLostToConcurrentChange() {
        Order paid = orderIn(OrderStatus.PAID);
        Order requested = orderIn(OrderStatus.CANCEL_REQUESTED);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.get(7L, 1L)).thenReturn(paid);
        when(cancellation.request(7L, 1L, OrderCancelReason.OTHER))
            .thenThrow(new ClaimRequestKeyConflictException())
            .thenThrow(new CannotAcquireLockException("nowait"))
            .thenThrow(new LockTimeoutException("nowait"))
            .thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        when(cancellation.findReplay(7L, 1L)).thenReturn(Optional.of(requested), Optional.empty(), Optional.empty(), Optional.empty());
        when(presenter.toDetailResponse(requested, false)).thenReturn(response);

        assertThat(facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isSameAs(response);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isInstanceOfSatisfying(OrderException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));
        }
        verifyNoInteractions(pipeline);
    }

    @Test
    @DisplayName("요청 키가 아닌 제약 위반은 재생 · 409 로 바꾸지 않고 그대로 나간다")
    void otherIntegrityViolationPropagates() {
        Order paid = orderIn(OrderStatus.PAID);
        when(processor.get(7L, 1L)).thenReturn(paid);
        when(cancellation.request(7L, 1L, OrderCancelReason.OTHER)).thenThrow(new DataIntegrityViolationException("ck_claim_cancellation_payment"));

        assertThatThrownBy(() -> facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isInstanceOf(DataIntegrityViolationException.class);
        verify(cancellation, never()).findReplay(anyLong(), anyLong());
        verifyNoInteractions(pipeline);
    }

    @Test
    @DisplayName("접수의 업무 거절(ORDER_020 · 033 · 034)은 그대로 나간다")
    void requestRejectionsPropagate() {
        Order paid = orderIn(OrderStatus.PAID);
        when(processor.get(7L, 1L)).thenReturn(paid);
        when(cancellation.request(7L, 1L, OrderCancelReason.OTHER)).thenThrow(new OrderException(OrderErrorCode.CANCELLATION_PAYMENT_MISSING));

        assertThatThrownBy(() -> facade.cancel(7L, 1L, OrderCancelReason.OTHER)).isInstanceOfSatisfying(OrderException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.CANCELLATION_PAYMENT_MISSING));
        verifyNoInteractions(pipeline, presenter);
    }

    @Test
    void concurrentPurchaseConfirmationIsIdempotent() {
        Order confirmed = orderIn(OrderStatus.PURCHASE_CONFIRMED);
        OrderDetailResponse response = OrderDetailResponse.builder().orderId("1").build();
        when(processor.confirmPurchase(7L, 1L)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        when(processor.get(7L, 1L)).thenReturn(confirmed);
        when(presenter.toDetailResponse(confirmed, false)).thenReturn(response);

        assertThat(facade.confirmPurchase(7L, 1L)).isSameAs(response);
    }

    @Test
    void concurrentReturnRejectsPurchaseConfirmation() {
        when(processor.confirmPurchase(7L, 1L)).thenThrow(new ObjectOptimisticLockingFailureException("orders", 1L));
        Order returned = orderIn(OrderStatus.RETURN_REQUESTED);
        when(processor.get(7L, 1L)).thenReturn(returned);

        assertThatThrownBy(() -> facade.confirmPurchase(7L, 1L)).isInstanceOfSatisfying(OrderException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));
    }
}
