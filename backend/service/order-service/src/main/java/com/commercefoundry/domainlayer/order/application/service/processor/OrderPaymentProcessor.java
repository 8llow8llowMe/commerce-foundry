package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.model.PaymentApprovalTarget;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentProcessor {

    public static final String CONSUMER_PURPOSE = "payment-approved";
    private final OrderRepositoryPort orderRepositoryPort;
    private final OrderPaymentRepositoryPort orderPaymentRepositoryPort;
    private final OrderEventPort orderEventPort;
    private final ProcessedEventStore processedEventStore;
    private final TopicNames topicNames;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    @Transactional(readOnly = true, timeout = 10)
    public boolean isProcessed(UUID eventId) {
        return processedEventStore.exists(group(), eventId);
    }

    @Transactional(readOnly = true, timeout = 10)
    public PaymentApprovalTarget inspect(VerifiedPayment payment, UUID eventId) {
        if (isRecorded(payment.paymentId(), eventId)) {
            return new PaymentApprovalTarget(true, null, null);
        }
        Order order = orderRepositoryPort.findById(payment.orderId()).orElse(null);
        return new PaymentApprovalTarget(false, order, rejection(payment, order));
    }

    /**
     * 원격 I/O 없이 DB 구간만 새 트랜잭션으로 묶는다. 충돌은 바깥 Facade가 잡고 새 트랜잭션에서 재판정한다.
     * 주문 @Version flush → payment INSERT → inbox → 회신 outbox 순서다. 뒤 단계 실패는 모두 롤백한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void complete(PaymentApprovalCommand command, VerifiedPayment payment, StockConfirmation confirmation) {
        if (isRecorded(payment.paymentId(), command.eventId())) {
            record(command.eventId());
            return;
        }
        Order before = orderRepositoryPort.findById(payment.orderId()).orElse(null);
        Result rejected = rejection(payment, before);
        if (rejected == null && (confirmation == null || !confirmation.isConfirmed())) {
            rejected = Result.REJECTED_STOCK_UNAVAILABLE;
        }
        OrderPayment record;
        if (rejected == null) {
            Order paid = before.markPaid(confirmation.reservationId());
            orderRepositoryPort.saveStatusChange(paid,
                OrderStatusHistory.changedByService(nextId(), before, paid, OrderStatusHistory.PAYMENT_APPROVED));
            record = OrderPayment.paid(nextId(), paid, payment.paymentId(), payment.amount(), payment.approvedAt(), command.eventId());
        } else if (rejected == Result.REJECTED_ORDER_CANCELED && confirmation != null && confirmation.isConfirmed()) {
            record = OrderPayment.canceledAfterConfirmation(nextId(), payment.orderId(), payment.paymentId(), payment.memberId(), payment.amount(),
                payment.approvedAt(), confirmation.reservationId(), command.eventId());
        } else {
            record = OrderPayment.rejected(nextId(), payment.orderId(), payment.paymentId(), payment.memberId(), payment.amount(), payment.approvedAt(),
                rejected, command.eventId());
        }
        orderPaymentRepositoryPort.insert(record);
        record(command.eventId());
        if (record.result() == Result.PAID) {
            orderEventPort.paid(record, clock.instant());
        } else if (!payment.fullyCanceled()) {
            orderEventPort.paymentRejected(record);
        }
        if (!payment.done()) {
            log.warn("payment approval ignored, payment not done. eventId={}, paymentId={}, fullyCanceled={}",
                command.eventId(), payment.paymentId(), payment.fullyCanceled());
        }
    }

    private boolean isRecorded(long paymentId, UUID eventId) {
        return processedEventStore.exists(group(), eventId) || orderPaymentRepositoryPort.findByPaymentId(paymentId).isPresent();
    }

    private Result rejection(VerifiedPayment payment, Order order) {
        if (!payment.done()) {
            return Result.REJECTED_PAYMENT_NOT_DONE;
        }
        if (order == null) {
            return Result.REJECTED_ORDER_NOT_FOUND;
        }
        if (order.memberId() != payment.memberId() || !order.quote().totalAmount().equals(payment.amount())) {
            return Result.REJECTED_PAYMENT_MISMATCH;
        }
        if (order.status() == OrderStatus.CANCELED) {
            return Result.REJECTED_ORDER_CANCELED;
        }
        if (!order.isAwaitingPaymentApproval() || orderPaymentRepositoryPort.findPaidByOrderId(order.id()).isPresent()) {
            return Result.REJECTED_ALREADY_PAID;
        }
        return null;
    }

    private void record(UUID eventId) {
        if (!processedEventStore.exists(group(), eventId)) {
            processedEventStore.record(group(), eventId, "payment-approved", clock.instant());
        }
    }

    private long nextId() {
        return snowflakeIdGenerator.generateId();
    }

    private String group() {
        return topicNames.consumerGroup(CONSUMER_PURPOSE);
    }
}
