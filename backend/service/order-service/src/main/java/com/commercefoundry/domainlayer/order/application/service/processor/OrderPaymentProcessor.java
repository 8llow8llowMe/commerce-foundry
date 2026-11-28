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
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Origin;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import com.commercefoundry.global.properties.OrderPaymentRestorationProperties;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
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
    private final OrderStockRestorationRepositoryPort orderStockRestorationRepositoryPort;
    private final OrderPaymentRestorationProperties restorationProperties;
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

    /** 원격 확정 전에 후보를 커밋한다. PAID 및 복원 대상으로의 확정과 동일한 주문 잠금을 사용한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean prepareConfirmation(VerifiedPayment payment, long reservationId, UUID eventId) {
        Order order = orderRepositoryPort.findByIdAndMemberIdForConfirmation(payment.orderId(), payment.memberId()).orElse(null);
        if (rejection(payment, order) != null || isRecorded(payment.paymentId(), eventId)
            || orderPaymentRepositoryPort.existsRestorationIntent(payment.orderId(), reservationId)) {
            return false;
        }
        Optional<OrderStockRestoration> work = orderStockRestorationRepositoryPort.findByReservationId(reservationId);
        if (work.isPresent()) {
            OrderStockRestoration candidate = work.get();
            if (!isConfirmationAttempt(candidate, payment.orderId())) {
                return false;
            }
            if (candidate.paymentId() != payment.paymentId()) {
                orderStockRestorationRepositoryPort.addConfirmationParticipant(candidate.id(), payment.paymentId());
            }
            return true;
        }
        orderStockRestorationRepositoryPort.insert(OrderStockRestoration.forConfirmationAttempt(nextId(), payment.orderId(),
            payment.paymentId(), reservationId, nextId(), clock.instant().plus(restorationProperties.minAge())));
        return true;
    }

    /** PRODUCT_003/025는 해당 선점의 확정이 불가능함을 보여준다. 모호한 응답의 후보는 보존한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void discardUnavailableConfirmation(VerifiedPayment payment, long reservationId) {
        if (orderRepositoryPort.findByIdAndMemberIdForUpdate(payment.orderId(), payment.memberId()).isPresent()) {
            takeConfirmationAttempt(payment.orderId(), reservationId);
        }
    }

    /** 프로브 자격과 거절 reason은 별개다. 이미 결제된 주문의 선점을 해제 프로브로 건드리지 않는다. */
    @Transactional(readOnly = true, timeout = 5)
    public boolean canProbe(VerifiedPayment payment, Order order) {
        if (order == null || (!payment.canceled() && !(payment.done() && order.status() == OrderStatus.CANCELED))) {
            return false;
        }
        if (order.memberId() != payment.memberId() || !order.quote().totalAmount().equals(payment.amount())
            || orderPaymentRepositoryPort.findPaidByOrderId(order.id()).isPresent()) {
            return false;
        }
        requireRestorableOrder(order);
        return true;
    }

    /**
     * product 장애로 실패한 해제 프로브를 스윕에 넘겨도 되는가(#582) — 스윕이 켜져 있어야 확정 시도 작업 행으로 남은 확정 선점을 복원할 주체가 있다.
     * 꺼져 있으면 거절을 미루고 재전달 · DLT 로 간다.
     */
    public boolean canDeferProbeToSweep() {
        return restorationProperties.sweep().enabled();
    }

    /**
     * 원격 I/O 없이 DB 구간만 새 트랜잭션으로 묶는다. 충돌은 바깥 Facade가 잡고 새 트랜잭션에서 재판정한다.
     * orders NOWAIT 잠금 → payment 조회/의도와 복원 작업 INSERT 또는 PAID 저장 → inbox → 회신 outbox 순서다. 뒤 단계 실패는 모두 롤백한다.
     * 의도 INSERT는 주문 버전을 바꾸지 않으므로 @Version만으로 PAID와 상호 배제되지 않는다. 원격 I/O는 이 잠금 밖이다.
     *
     * <p>확정 뒤 취소가 이긴 경합(#308 PR-C1)은 거절 기록(확정 선점 · 새 복원 클레임 아이디)만 넣고 inbox · 회신은 남기지 않는다 — 돌려준 대상으로 Facade 가
     * tx 밖에서 복원하고 {@link #completeRestoration} 이 마무리한다. 이미 그런 기록이 복원 전이면(복원 실패 뒤 재전달) 새로 쓰지 않고 그 기록을 돌려준다.
     *
     * @return 재고 복원이 남은 기록. 없으면 빈 값 — 반영이 끝났다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<StockRestorationTarget> complete(PaymentApprovalCommand command, VerifiedPayment payment, StockConfirmation confirmation) {
        Order before = orderRepositoryPort.findByIdAndMemberIdForUpdate(payment.orderId(), payment.memberId())
            .orElseGet(() -> orderRepositoryPort.findById(payment.orderId()).orElse(null));
        Optional<OrderPayment> existing = orderPaymentRepositoryPort.findByPaymentId(payment.paymentId());
        existing.ifPresent(record -> verifyRecord(record, command, payment));
        if (existing.isPresent() && existing.get().awaitsRestoration()) {
            Order canceled = orderRepositoryPort.findById(existing.get().orderId())
                .orElseThrow(() -> new IllegalStateException("restorable payment record without order: orderId=" + existing.get().orderId()));
            if (canceled.memberId() != payment.memberId() || !canceled.quote().totalAmount().equals(payment.amount())
                || orderPaymentRepositoryPort.findPaidByOrderId(canceled.id()).isPresent()) {
                throw new NonRetryableEventException("restoration order ownership mismatch");
            }
            requireRestorableOrder(canceled);
            ensureRestorationWork(existing.get());
            return Optional.of(new StockRestorationTarget(existing.get(), canceled));
        }
        if (existing.isPresent() || processedEventStore.exists(group(), command.eventId())) {
            if (confirmation != null && confirmation.isConfirmed() && !accountsFor(existing, payment.orderId(), confirmation.reservationId())) {
                // 선행 커밋된 추적 증거가 없는 확정은 소유권을 확인할 때까지 DLT에서 보류한다.
                log.error("confirmed stock reservation not recorded by committed payment reflection. orderId={}, paymentId={}, reservationId={}, eventId={}",
                    payment.orderId(), payment.paymentId(), confirmation.reservationId(), command.eventId());
                throw new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT);
            }
            record(command.eventId());
            return Optional.empty();
        }
        Result rejected = rejection(payment, before);
        boolean fenced = confirmation != null && confirmation.isConfirmed()
            && hasRestorationIntent(payment.orderId(), confirmation.reservationId());
        if (rejected == null && (confirmation == null || !confirmation.isConfirmed() || fenced)) {
            rejected = Result.REJECTED_STOCK_UNAVAILABLE;
        }
        OrderPayment record;
        if (rejected == null) {
            takeConfirmationAttempt(before.id(), confirmation.reservationId());
            Order paid = before.markPaid(confirmation.reservationId());
            orderRepositoryPort.saveStatusChange(paid,
                OrderStatusHistory.changedByService(nextId(), before, paid, OrderStatusHistory.PAYMENT_APPROVED));
            record = OrderPayment.paid(nextId(), paid, payment.paymentId(), payment.amount(), payment.approvedAt(), command.eventId());
        } else if (rejected.restorableRejection() && confirmation != null && confirmation.isConfirmed() && before != null
            && before.memberId() == payment.memberId() && before.quote().totalAmount().equals(payment.amount())) {
            if (!fenced && orderPaymentRepositoryPort.findPaidByOrderId(payment.orderId()).isEmpty()) {
                requireRestorableOrder(before);
                long claimId = takeConfirmationAttempt(before.id(), confirmation.reservationId()).map(OrderStockRestoration::claimId)
                    .orElseGet(this::nextId);
                OrderPayment restorable = orderPaymentRepositoryPort.insert(OrderPayment.rejectedAfterConfirmation(nextId(), payment.orderId(), payment.paymentId(),
                    payment.memberId(), payment.amount(), payment.approvedAt(), rejected, confirmation.reservationId(), command.eventId(), claimId));
                ensureRestorationWork(restorable);
                return Optional.of(new StockRestorationTarget(restorable, before));
            }
            // PAID의 선점은 클레임이 담당하고, 다른 후보는 독립 스윕이 판정한다.
            log.warn("payment reservation excluded from inline restoration. orderId={}, paymentId={}", payment.orderId(), payment.paymentId());
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
     * 재고 복원 뒤 마무리 — 결제 복원 시각 조건부 UPDATE → 작업 RESTORED → inbox → 저장된 거절 reason의 회신 outbox 를 한 커밋으로 남긴다.
     * 복원 시각을 이번에 채우지 못했으면(같은 결제의 다른 처리가 먼저 마무리) inbox 만 남기고 회신을 다시 쌓지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void completeRestoration(UUID eventId, OrderPayment restored, VerifiedPayment payment) {
        ensureRestorationWork(restored);
        Instant now = clock.instant();
        boolean marked = orderPaymentRepositoryPort.markRestored(restored.id(), now);
        orderStockRestorationRepositoryPort.markRestored(restored.restorationClaimId(), now);
        record(eventId);
        // 일반 거절과 같다 — 재전달 때 다시 조회한 결제가 이미 전액 취소면 환불할 돈이 없어 회신하지 않는다.
        if (marked && !payment.fullyCanceled()) {
            orderEventPort.paymentRejected(restored);
        }
    }

    /** 결제 복원 의도와 같은 트랜잭션이다. 기존 작업은 출처 전체를 대조하고 시도·다음 시각·상태를 보존한다. */
    private void ensureRestorationWork(OrderPayment record) {
        OrderStockRestoration work = orderStockRestorationRepositoryPort.findByClaimId(record.restorationClaimId()).orElse(null);
        if (work == null) {
            orderStockRestorationRepositoryPort.insert(OrderStockRestoration.forPaymentRecord(nextId(), record,
                clock.instant().plus(restorationProperties.minAge())));
        } else if (work.claimId() != record.restorationClaimId() || work.orderId() != record.orderId() || work.paymentId() != record.paymentId()
            || work.stockReservationId() != record.stockReservationId() || work.origin() != OrderStockRestoration.Origin.PAYMENT_RECORD) {
            throw new NonRetryableEventException("restoration work ownership mismatch");
        }
    }

    /** 같은 주문의 결제 기록이나 복원 원장이 확정 선점을 책임지는지 확인한다. */
    private boolean accountsFor(Optional<OrderPayment> existing, long orderId, long reservationId) {
        if (existing.isPresent() && Long.valueOf(reservationId).equals(existing.get().stockReservationId())) {
            return true;
        }
        return orderPaymentRepositoryPort.existsRestorationIntent(orderId, reservationId)
            || orderStockRestorationRepositoryPort.findByReservationId(reservationId).filter(work -> work.orderId() == orderId).isPresent()
            || orderPaymentRepositoryPort.findPaidByOrderId(orderId).map(paid -> Long.valueOf(reservationId).equals(paid.stockReservationId())).orElse(false);
    }

    private boolean hasRestorationIntent(long orderId, long reservationId) {
        return orderPaymentRepositoryPort.existsRestorationIntent(orderId, reservationId)
            || orderStockRestorationRepositoryPort.findByReservationId(reservationId)
                .filter(work -> !isConfirmationAttempt(work, orderId)).isPresent();
    }

    /** PAID 또는 PAYMENT_RECORD가 선점을 인수한다. 실패하면 원래 후보도 돌아온다. */
    private Optional<OrderStockRestoration> takeConfirmationAttempt(long orderId, long reservationId) {
        return orderStockRestorationRepositoryPort.findByReservationId(reservationId)
            .filter(work -> isConfirmationAttempt(work, orderId))
            .map(work -> {
                if (!orderStockRestorationRepositoryPort.delete(work)) {
                    throw new IllegalStateException("confirmation attempt changed under order lock");
                }
                return work;
            });
    }

    private static boolean isConfirmationAttempt(OrderStockRestoration work, long orderId) {
        return work.orderId() == orderId && work.origin() == Origin.CONFIRMATION_ATTEMPT && work.status() == Status.RESTORE_PENDING;
    }

    private boolean isRecorded(long paymentId, UUID eventId) {
        return processedEventStore.exists(group(), eventId) || orderPaymentRepositoryPort.findByPaymentId(paymentId).isPresent();
    }

    private Result rejection(VerifiedPayment payment, Order order) {
        if (!payment.done() && !payment.canceled()) {
            return Result.REJECTED_PAYMENT_NOT_DONE;
        }
        if (order == null) {
            return Result.REJECTED_ORDER_NOT_FOUND;
        }
        if (order.memberId() != payment.memberId() || !order.quote().totalAmount().equals(payment.amount())) {
            return Result.REJECTED_PAYMENT_MISMATCH;
        }
        if (payment.canceled()) {
            return Result.REJECTED_PAYMENT_NOT_DONE;
        }
        if (order.status() == OrderStatus.CANCELED) {
            return Result.REJECTED_ORDER_CANCELED;
        }
        if (!order.isAwaitingPaymentApproval() || orderPaymentRepositoryPort.findPaidByOrderId(order.id()).isPresent()) {
            return Result.REJECTED_ALREADY_PAID;
        }
        return null;
    }

    private static void requireRestorableOrder(Order order) {
        if (!order.isAwaitingPaymentApproval() && order.status() != OrderStatus.CANCELED) {
            throw new NonRetryableEventException("legacy paid order without payment record");
        }
    }

    private static void verifyRecord(OrderPayment record, PaymentApprovalCommand command, VerifiedPayment payment) {
        if (record.paymentId() != payment.paymentId() || record.orderId() != payment.orderId() || record.memberId() != payment.memberId()
            || !record.totalAmount().equals(payment.amount())
            || ((record.approvedAt() != null || record.awaitsRestoration()) && !Objects.equals(record.approvedAt(), micros(command.approvedAt())))
            || (record.approvedAt() != null && payment.approvedAt() != null && !Objects.equals(record.approvedAt(), micros(payment.approvedAt())))) {
            throw new NonRetryableEventException("stored payment notification mismatch");
        }
    }

    private static Instant micros(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
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
