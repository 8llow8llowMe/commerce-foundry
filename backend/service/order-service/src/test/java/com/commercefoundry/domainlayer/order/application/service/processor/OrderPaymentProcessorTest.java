package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockConfirmationRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결제 반영 DB 구간의 PR-C1 판정 — 확정 선점 누락 감지와 복원 마무리의 회신 여부. */
class OrderPaymentProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final OrderPaymentRepositoryPort payments = mock(OrderPaymentRepositoryPort.class);
    private final OrderStockConfirmationRepositoryPort evidence = mock(OrderStockConfirmationRepositoryPort.class);
    private final OrderEventPort events = mock(OrderEventPort.class);
    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final OrderPaymentProcessor processor = new OrderPaymentProcessor(orders, payments, evidence, events, inbox,
        new TopicNames("commerce-foundry.test", "order"), new SnowflakeIdGenerator(1, 1), Clock.fixed(NOW, ZoneOffset.UTC));
    private final PaymentApprovalCommand command = new PaymentApprovalCommand(UUID.randomUUID(), 10, 20, 30, krw(4000), NOW, "CARD");
    private final VerifiedPayment done = new VerifiedPayment(10, 20, 30, krw(4000), NOW, "DONE");
    private final OrderPayment restorable = OrderPayment.canceledAfterConfirmation(1, 20, 10, 30, krw(4000), NOW, 77, command.eventId(), 555);

    @Test
    @DisplayName("이번 실행이 확정한 선점을 커밋된 기록이 가리키지 않으면 inbox 만 남기지 않고 STOCK_RESERVATION_INCONSISTENT 다(DLT)")
    void confirmedReservationNotAccountedForIsInconsistent() {
        when(payments.findByPaymentId(10)).thenReturn(Optional.of(
            OrderPayment.rejected(2, 20, 10, 30, krw(4000), NOW, OrderPayment.Result.REJECTED_ORDER_CANCELED, UUID.randomUUID())));
        when(payments.findPaidByOrderId(20)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.complete(command, done, StockConfirmation.confirmed(77)))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT));
        verify(inbox, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("기존 기록이 같은 선점(이 결제의 완료 · 같은 주문의 결제 완료)이면 inbox 만 남긴다")
    void confirmedReservationAccountedForRecordsInbox() {
        OrderPayment paidByOther = new OrderPayment(3, 20, 11, 30, krw(4000), NOW, OrderPayment.Result.PAID, 77L, UUID.randomUUID(), null, null, null);
        when(payments.findByPaymentId(10)).thenReturn(Optional.of(
            OrderPayment.rejected(2, 20, 10, 30, krw(4000), NOW, OrderPayment.Result.REJECTED_ALREADY_PAID, UUID.randomUUID())));
        when(payments.findPaidByOrderId(20)).thenReturn(Optional.of(paidByOther));

        assertThat(processor.complete(command, done, StockConfirmation.confirmed(77))).isEmpty();
        verify(inbox).record(any(), eq(command.eventId()), any(), any());
    }

    @Test
    @DisplayName("복원 마무리 — 재조회한 결제가 전액 취소면 거절 회신을 내지 않는다(일반 거절과 같다). 아니면 한 번 낸다")
    void completeRestorationSkipsReplyForFullyCanceledPayment() {
        when(payments.markRestored(anyLong(), any())).thenReturn(true);

        processor.completeRestoration(command.eventId(), restorable, new VerifiedPayment(10, 20, 30, krw(4000), NOW, "CANCELED"));
        verify(events, never()).paymentRejected(any());

        processor.completeRestoration(command.eventId(), restorable, done);
        verify(events).paymentRejected(restorable);
    }

    @Test
    @DisplayName("복원 마무리 — 복원 시각을 이번에 채우지 못했으면(다른 처리가 먼저) 회신하지 않는다")
    void completeRestorationWithoutMarkDoesNotReply() {
        when(payments.markRestored(anyLong(), any())).thenReturn(false);

        processor.completeRestoration(command.eventId(), restorable, done);

        verify(events, never()).paymentRejected(any());
        verify(inbox).record(any(), eq(command.eventId()), any(), any());
    }

    @Test
    void anotherPaymentsRestorationIntentAccountsForConfirmedReservation() {
        when(payments.findByPaymentId(10)).thenReturn(Optional.of(
            OrderPayment.rejected(2, 20, 10, 30, krw(4000), NOW, OrderPayment.Result.REJECTED_ORDER_CANCELED, UUID.randomUUID())));
        when(payments.existsRestorationIntent(20, 77)).thenReturn(true);

        assertThat(processor.complete(command, done, StockConfirmation.confirmed(77))).isEmpty();

        verify(inbox).record(any(), eq(command.eventId()), any(), any());
        verify(payments, never()).insert(any());
    }
}
