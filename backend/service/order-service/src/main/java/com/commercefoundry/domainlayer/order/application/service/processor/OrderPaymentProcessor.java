package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.PaymentApprovalTarget;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.StockRestorationTarget;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockConfirmationRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final OrderStockConfirmationRepositoryPort orderStockConfirmationRepositoryPort;
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
     * 재선점 증거를 남긴다 (#308 PR-C1) — 재선점이 새 선점 아이디를 줬을 때 그 선점을 확정하기 <b>전에</b> 짧은 새 트랜잭션으로 커밋한다.
     * 실패하면 예외가 그대로 나가 확정을 부르지 않는다(재전달로 다시 한다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void recordReReservation(long paymentId, long orderId, long stockReservationId, UUID eventId) {
        orderStockConfirmationRepositoryPort.upsert(paymentId, orderId, stockReservationId, eventId);
    }

    /**
     * 취소 주문 복구창 프로브의 후보 선점 (#308 PR-C1) — 재선점 증거(있으면) → 주문의 선점 아이디 순서다. 확정 뒤 DB 기록 전에 끊기면 메모리의 확정 선점 아이디는 사라지지만
     * 확정 대상은 둘 중 하나로 남아 있다(첫 확정은 주문의 선점, 재선점 확정은 증거를 먼저 커밋한 뒤에만 부른다).
     */
    @Transactional(readOnly = true, timeout = 10)
    public List<Long> confirmationCandidates(long paymentId, Order order) {
        List<Long> candidates = new ArrayList<>(2);
        orderStockConfirmationRepositoryPort.findStockReservationId(paymentId).ifPresent(candidates::add);
        candidates.add(order.stockReservationId());
        return candidates;
    }

    /**
     * 원격 I/O 없이 DB 구간만 새 트랜잭션으로 묶는다. 충돌은 바깥 Facade가 잡고 새 트랜잭션에서 재판정한다.
     * 주문 @Version flush → payment INSERT → inbox → 회신 outbox 순서다. 뒤 단계 실패는 모두 롤백한다.
     *
     * <p>확정 뒤 취소가 이긴 경합(#308 PR-C1)은 거절 기록(확정 선점 · 새 복원 클레임 아이디)만 넣고 inbox · 회신은 남기지 않는다 — 돌려준 대상으로 Facade 가
     * tx 밖에서 복원하고 {@link #completeRestoration} 이 마무리한다. 이미 그런 기록이 복원 전이면(복원 실패 뒤 재전달) 새로 쓰지 않고 그 기록을 돌려준다.
     *
     * @return 재고 복원이 남은 기록. 없으면 빈 값 — 반영이 끝났다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<StockRestorationTarget> complete(PaymentApprovalCommand command, VerifiedPayment payment, StockConfirmation confirmation) {
        Optional<OrderPayment> existing = orderPaymentRepositoryPort.findByPaymentId(payment.paymentId());
        if (existing.isPresent() && existing.get().awaitsRestoration()) {
            Order canceled = orderRepositoryPort.findById(existing.get().orderId())
                .orElseThrow(() -> new IllegalStateException("restorable payment record without order: orderId=" + existing.get().orderId()));
            return Optional.of(new StockRestorationTarget(existing.get(), canceled));
        }
        if (existing.isPresent() || processedEventStore.exists(group(), command.eventId())) {
            if (confirmation != null && confirmation.isConfirmed() && !accountsFor(existing, payment.orderId(), confirmation.reservationId())) {
                // 이번 실행이 확정한 선점을 어느 기록도 가리키지 않는다 — 다른 처리가 확정 전 취소(프로브 200)로 거절을 먼저 커밋했다.
                // inbox 만 남기면 확정 선점이 조용히 샌다. 자동 복원은 후속 이슈 — 지금은 DLT 로 사람이 본다.
                log.error("confirmed stock reservation not recorded by committed payment reflection. orderId={}, paymentId={}, reservationId={}, eventId={}",
                    payment.orderId(), payment.paymentId(), confirmation.reservationId(), command.eventId());
                throw new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT);
            }
            record(command.eventId());
            return Optional.empty();
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
            if (orderPaymentRepositoryPort.findPaidByOrderId(payment.orderId()).isEmpty()) {
                OrderPayment restorable = orderPaymentRepositoryPort.insert(OrderPayment.canceledAfterConfirmation(nextId(), payment.orderId(), payment.paymentId(),
                    payment.memberId(), payment.amount(), payment.approvedAt(), confirmation.reservationId(), command.eventId(), nextId()));
                return Optional.of(new StockRestorationTarget(restorable, before));
            }
            // 다른 결제가 이미 결제 완료한 뒤 취소된 주문 — 확정 선점은 이긴 결제의 것이고 그 취소 클레임이 복원한다. 여기서 복원하면 두 번 는다.
            log.warn("canceled order already paid by another payment, skipping restoration. orderId={}, paymentId={}", payment.orderId(), payment.paymentId());
            record = OrderPayment.rejected(nextId(), payment.orderId(), payment.paymentId(), payment.memberId(), payment.amount(), payment.approvedAt(),
                rejected, command.eventId());
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
        return Optional.empty();
    }

    /**
     * 재고 복원 뒤 마무리 (#308 PR-C1) — 복원 시각 조건부 UPDATE → inbox → {@code order-payment-rejected(ORDER_CANCELED)} 회신 outbox 를 한 커밋으로 남긴다.
     * 복원 시각을 이번에 채우지 못했으면(같은 결제의 다른 처리가 먼저 마무리) inbox 만 남기고 회신을 다시 쌓지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void completeRestoration(UUID eventId, OrderPayment restored, VerifiedPayment payment) {
        boolean marked = orderPaymentRepositoryPort.markRestored(restored.id(), clock.instant());
        record(eventId);
        // 일반 거절과 같다 — 재전달 때 다시 조회한 결제가 이미 전액 취소면 환불할 돈이 없어 회신하지 않는다.
        if (marked && !payment.fullyCanceled()) {
            orderEventPort.paymentRejected(restored);
        }
    }

    /** 확정한 선점을 커밋된 기록이 이미 책임지는가 — 이 결제의 기록이 같은 선점을 가리키거나, 같은 주문의 결제 완료 기록이 그 선점이다. */
    private boolean accountsFor(Optional<OrderPayment> existing, long orderId, long reservationId) {
        if (existing.isPresent() && Long.valueOf(reservationId).equals(existing.get().stockReservationId())) {
            return true;
        }
        return orderPaymentRepositoryPort.findPaidByOrderId(orderId).map(paid -> Long.valueOf(reservationId).equals(paid.stockReservationId())).orElse(false);
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
