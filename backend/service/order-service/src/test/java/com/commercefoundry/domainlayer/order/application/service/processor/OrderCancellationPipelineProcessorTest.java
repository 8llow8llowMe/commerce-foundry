package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.CancellationStepOutcome;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 결제 후 취소 파이프라인 (#16 PR-2) — 환불 결과(SUCCEEDED · REJECTED · UNKNOWN · 예외) × 복원 결과(200 · 재시도성 · 종결)를 단계 결과로 바꿔 넘기는지,
 * 저장된 결제 아이디 · 금액만 보내는지, 버전에서 지면 멈추는지 본다. DB 단계(상한 · 상태 전이)는 {@code OrderCancellationProcessorTest} 가 본다.
 */
class OrderCancellationPipelineProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    private final OrderCancellationProcessor processor = mock(OrderCancellationProcessor.class);
    private final PaymentCancelPort paymentCancelPort = mock(PaymentCancelPort.class);
    private final StockRestorationPort stockRestorationPort = mock(StockRestorationPort.class);
    private final OrderCancellationPipelineProcessor pipeline = new OrderCancellationPipelineProcessor(processor, paymentCancelPort, stockRestorationPort);

    private static Order order() {
        long[] next = {100};
        // 같은 SKU 줄(맞춤 입력만 다름)은 복원에서 합친다
        Order placed = Order.place(1L, 7L, domesticQuote(quoteLine(9002, 5_000, 1), quoteLine(9001, 10_000, 2), quoteLine(9002, 5_000, 3)),
            () -> ++next[0], address("KR"), 99L, NOW);
        return new Order(placed.id(), 7L, OrderStatus.CANCELED, placed.quote(), placed.itemIds(), placed.shippingAddress(), 555L, placed.paymentDueAt(),
            null, null, Instant.EPOCH, 3L);
    }

    private static Claim stored(Claim claim, long version) {
        return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), claim.items(), NOW, version);
    }

    private static Claim requested() {
        Order order = order();
        long[] next = {500};
        Order paid = new Order(order.id(), 7L, OrderStatus.PAID, order.quote(), order.itemIds(), order.shippingAddress(), 555L, order.paymentDueAt(),
            null, null, Instant.EPOCH, 1L);
        return stored(Claim.requestCancellation(400L, paid, 77L, CancellationRefundPolicy.fullRefund(paid), OrderCancelReason.OTHER, () -> ++next[0], NOW,
            Duration.ofMinutes(2)), 0L);
    }

    private static Claim refunded() {
        return stored(requested().markRefunded(NOW), 1L);
    }

    static Stream<Arguments> refundOutcomes() {
        return Stream.of(
            Arguments.of(PaymentCancelQueryResult.succeeded(), CancellationStepOutcome.done()),
            Arguments.of(PaymentCancelQueryResult.rejected("PAYMENT_016"), CancellationStepOutcome.failed("PAYMENT_016")),
            Arguments.of(PaymentCancelQueryResult.unknown(), CancellationStepOutcome.retry()));
    }

    @ParameterizedTest
    @MethodSource("refundOutcomes")
    @DisplayName("환불 결과를 단계 결과로 바꿔 tx2 에 넘기고, 저장된 결제 아이디 · 클레임 아이디 · 환불액만 보낸다")
    void refundOutcomeIsRecorded(PaymentCancelQueryResult result, CancellationStepOutcome expected) {
        Claim claim = requested();
        when(paymentCancelPort.cancel(77L, 400L, claim.expectedRefund())).thenReturn(result);
        Claim afterRefund = expected.kind() == CancellationStepOutcome.Kind.DONE ? stored(claim.markRefunded(NOW), 1L) : claim;
        when(processor.recordRefund(claim, expected)).thenReturn(afterRefund);
        when(processor.getOrder(claim.orderId())).thenReturn(order());
        when(processor.recordRestoration(any(), any())).thenAnswer(invocation -> stored(((Claim) invocation.getArgument(0)).complete(), 2L));

        Claim last = pipeline.progress(claim);

        verify(paymentCancelPort).cancel(77L, 400L, krw(claim.expectedRefund().amount()));
        verify(processor).recordRefund(claim, expected);
        if (expected.kind() == CancellationStepOutcome.Kind.DONE) {
            assertThat(last.status()).isEqualTo(Claim.Status.COMPLETED);
        } else {
            assertThat(last).isSameAs(claim);
            verifyNoInteractions(stockRestorationPort);
            verify(processor, never()).recordRestoration(any(), any());
        }
    }

    @Test
    @DisplayName("환불 호출이 Feign 밖 예외(토큰 발급 실패 등)를 던져도 미확정으로 남긴다")
    void refundRuntimeExceptionIsRetry() {
        Claim claim = requested();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("token issuer down"));
        when(processor.recordRefund(claim, CancellationStepOutcome.retry())).thenReturn(claim);

        pipeline.progress(claim);

        verify(processor).recordRefund(claim, CancellationStepOutcome.retry());
        verifyNoInteractions(stockRestorationPort);
    }

    static Stream<Arguments> restorationFailures() {
        return Stream.of(
            Arguments.of(StockReservationException.Failure.NOT_CONFIRMED, CancellationStepOutcome.failed("PRODUCT_027")),
            Arguments.of(StockReservationException.Failure.QUANTITY_EXCEEDED, CancellationStepOutcome.failed("PRODUCT_028")),
            Arguments.of(StockReservationException.Failure.RESTORATION_MISMATCH, CancellationStepOutcome.failed("PRODUCT_029")),
            Arguments.of(StockReservationException.Failure.NOT_FOUND, CancellationStepOutcome.failed("PRODUCT_003")),
            Arguments.of(StockReservationException.Failure.CONFLICT, CancellationStepOutcome.retry()),
            Arguments.of(StockReservationException.Failure.UNKNOWN, CancellationStepOutcome.retry()),
            Arguments.of(StockReservationException.Failure.REJECTED, CancellationStepOutcome.retry()));
    }

    @ParameterizedTest
    @MethodSource("restorationFailures")
    @DisplayName("복원 대기면 환불을 다시 보내지 않고 복원만 — 종결 거절은 PRODUCT 코드, 그 밖은 미확정이다")
    void restorationFailureIsRecorded(StockReservationException.Failure failure, CancellationStepOutcome expected) {
        Claim claim = refunded();
        when(processor.getOrder(claim.orderId())).thenReturn(order());
        doThrow(new StockReservationException(failure)).when(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());
        when(processor.recordRestoration(claim, expected)).thenReturn(claim);

        pipeline.progress(claim);

        verify(processor).recordRestoration(claim, expected);
        verifyNoInteractions(paymentCancelPort);
    }

    @Test
    @DisplayName("복원은 클레임 아이디 · 주문의 확정 선점 · 클레임 항목을 SKU 별로 합친 수량(오름차순)으로 보낸다")
    void restorationSendsClaimQuantitiesBySku() {
        Claim claim = refunded();
        when(processor.getOrder(claim.orderId())).thenReturn(order());
        when(processor.recordRestoration(claim, CancellationStepOutcome.done())).thenReturn(stored(claim.complete(), 2L));

        assertThat(pipeline.progress(claim).status()).isEqualTo(Claim.Status.COMPLETED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<SortedMap<Long, Integer>> quantities = ArgumentCaptor.forClass(SortedMap.class);
        verify(stockRestorationPort).restoreCancellation(org.mockito.ArgumentMatchers.eq(400L), org.mockito.ArgumentMatchers.eq(555L), quantities.capture());
        assertThat(quantities.getValue()).isEqualTo(new TreeMap<>(Map.of(9001L, 2, 9002L, 4)));
        assertThat(quantities.getValue().firstKey()).isEqualTo(9001L);
    }

    @Test
    @DisplayName("복원 호출의 그 밖의 예외도 미확정이다")
    void restorationRuntimeExceptionIsRetry() {
        Claim claim = refunded();
        when(processor.getOrder(claim.orderId())).thenReturn(order());
        doThrow(new IllegalStateException("boom")).when(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());
        when(processor.recordRestoration(claim, CancellationStepOutcome.retry())).thenReturn(claim);

        pipeline.progress(claim);

        verify(processor).recordRestoration(claim, CancellationStepOutcome.retry());
    }

    @Test
    @DisplayName("결과 반영에서 버전으로 지면(다른 워커가 처리) 조용히 멈추고 다음 단계로 가지 않는다")
    void stopsQuietlyWhenLosingVersion() {
        Claim claim = requested();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        when(processor.recordRefund(claim, CancellationStepOutcome.done())).thenThrow(new ObjectOptimisticLockingFailureException("claim", 400L));

        assertThat(pipeline.progress(claim)).isSameAs(claim);

        verifyNoInteractions(stockRestorationPort);
        verify(processor, never()).recordRestoration(any(), any());
    }

    @Test
    @DisplayName("종결 상태 클레임은 아무 원격 호출도 하지 않는다")
    void terminalClaimDoesNothing() {
        Claim completed = stored(refunded().complete(), 2L);

        assertThat(pipeline.progress(completed)).isSameAs(completed);

        verifyNoInteractions(paymentCancelPort, stockRestorationPort, processor);
    }

    @Test
    @DisplayName("환불 성공을 반영하다 버전에서 졌는데 클레임이 미확정(REFUND_UNCONFIRMED)으로 닫혀 있으면 다시 읽은 값으로 반영하고 복원까지 잇는다")
    void lateRefundSuccessAfterUnconfirmedIsApplied() {
        Claim claim = requested();
        Claim unconfirmed = stored(claim.failRefund(Claim.REFUND_UNCONFIRMED), 7L);
        Claim refunded = stored(unconfirmed.markRefunded(NOW), 8L);
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        when(processor.recordRefund(claim, CancellationStepOutcome.done())).thenThrow(new ObjectOptimisticLockingFailureException("ClaimEntity", 400L));
        when(processor.findClaim(claim.orderId())).thenReturn(java.util.Optional.of(unconfirmed));
        when(processor.recordRefund(unconfirmed, CancellationStepOutcome.done())).thenReturn(refunded);
        when(processor.getOrder(claim.orderId())).thenReturn(order());
        when(processor.recordRestoration(refunded, CancellationStepOutcome.done())).thenReturn(stored(refunded.complete(), 9L));

        assertThat(pipeline.progress(claim).status()).isEqualTo(Claim.Status.COMPLETED);

        verify(processor).recordRefund(unconfirmed, CancellationStepOutcome.done());
        verify(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("버전에서 진 뒤 다시 읽은 클레임이 미확정 종결이 아니거나(다른 워커가 처리) 결과가 성공이 아니면 멈춘다")
    void lostVersionOtherwiseStops() {
        Claim claim = requested();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded(), PaymentCancelQueryResult.unknown());
        when(processor.recordRefund(any(), any())).thenThrow(new ObjectOptimisticLockingFailureException("ClaimEntity", 400L));
        when(processor.findClaim(claim.orderId())).thenReturn(java.util.Optional.of(stored(claim.markRefunded(NOW), 3L)));

        assertThat(pipeline.progress(claim)).isSameAs(claim);
        assertThat(pipeline.progress(claim)).isSameAs(claim);

        verify(processor, times(2)).recordRefund(any(), any());
        verify(processor, times(1)).findClaim(claim.orderId());
        verifyNoInteractions(stockRestorationPort);
    }

    @Test
    @DisplayName("클레임 항목이 주문 스냅숏에 없으면(데이터 이상) 복원을 부르지 않고 바로 RESTORATION_DATA_INVALID 로 종결한다")
    void restorationDataInvalidIsTerminal() {
        Claim claim = refunded();
        Order other = order();
        Order mismatched = new Order(other.id(), 7L, OrderStatus.CANCELED, other.quote(), java.util.List.of(1L, 2L, 3L), other.shippingAddress(), 555L,
            other.paymentDueAt(), null, null, Instant.EPOCH, 3L);
        when(processor.getOrder(claim.orderId())).thenReturn(mismatched);
        CancellationStepOutcome expected = CancellationStepOutcome.failed(OrderCancellationPipelineProcessor.RESTORATION_DATA_INVALID);
        when(processor.recordRestoration(claim, expected)).thenReturn(claim);

        pipeline.progress(claim);

        verify(processor).recordRestoration(claim, expected);
        verifyNoInteractions(stockRestorationPort);
    }
}
