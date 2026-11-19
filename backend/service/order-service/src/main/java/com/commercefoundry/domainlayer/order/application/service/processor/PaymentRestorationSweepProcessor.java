package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.StockRestorationTarget;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Origin;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentRestorationSweepProcessor {

    private static final Set<String> APPROVAL_REQUIRED = Set.of("DONE", "PARTIAL_CANCELED", "CANCELED");
    private static final Set<String> INCOMPLETE_STATES = Set.of("READY", "IN_PROGRESS", "FAILED", "UNKNOWN", "ABORTED", "WAITING_FOR_DEPOSIT",
        "LATE_APPROVED");

    private final OrderStockRestorationRepositoryPort workRepository;
    private final OrderPaymentRepositoryPort payments;
    private final OrderRepositoryPort orders;
    private final OrderEventPort events;
    private final PaymentQueryPort paymentQuery;
    private final StockRestorationPort stock;
    private final Clock clock;

    @Transactional(readOnly = true, timeout = 5)
    public List<OrderStockRestoration> findDue(Instant now, int limit) {
        return workRepository.findDueRestorations(now, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean claim(OrderStockRestoration due, OrderStockRestoration claimed, Instant now) {
        return workRepository.claim(due, claimed, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<StockRestorationTarget> inspect(OrderStockRestoration claimed) {
        OrderStockRestoration current = currentWork(claimed);
        if (current.status() != Status.RESTORE_PENDING || !Objects.equals(current.nextAttemptAt(), claimed.nextAttemptAt())) {
            return Optional.empty();
        }
        OrderPayment payment = requirePayment(claimed);
        Order order = orders.findById(payment.orderId()).orElseThrow(Hold::ownershipMismatch);
        requireRestorableOrder(order, payment);
        if (payment.restoredAt() != null) {
            workRepository.markRestored(claimed.claimId(), payment.restoredAt());
            return Optional.empty();
        }
        return Optional.of(new StockRestorationTarget(payment, order));
    }

    @Transactional(propagation = Propagation.NEVER)
    public boolean restore(StockRestorationTarget target) {
        OrderPayment payment = target.orderPayment();
        PaymentQueryResult before = queryPayment(payment);
        try {
            stock.restoreCancellation(payment.restorationClaimId(), payment.stockReservationId(),
                OrderStockProcessor.quantitiesBySkuId(target.order().quote()));
        } catch (StockReservationException exception) {
            throw switch (exception.getFailure()) {
                case NOT_FOUND -> new Hold("PRODUCT_003");
                case NOT_CONFIRMED -> new Hold("PRODUCT_027");
                case QUANTITY_EXCEEDED -> new Hold("PRODUCT_028");
                case RESTORATION_MISMATCH -> new Hold("PRODUCT_029");
                default -> exception;
            };
        }
        PaymentQueryResult after = queryPayment(payment);
        if (approvalMismatch(before.approvedAt(), after.approvedAt())) {
            throw Hold.ownershipMismatch();
        }
        boolean fullyCanceled = "CANCELED".equals(after.statusCode());
        if ("CANCELED".equals(before.statusCode()) && !fullyCanceled) {
            throw new IllegalStateException("fully canceled payment status regressed");
        }
        return fullyCanceled;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void complete(OrderStockRestoration claimed, boolean fullyCanceled) {
        OrderPayment payment = requirePayment(claimed);
        Order order = orders.findByIdAndMemberIdForUpdate(payment.orderId(), payment.memberId()).orElseThrow(Hold::ownershipMismatch);
        requireRestorableOrder(order, payment);
        OrderStockRestoration current = currentWork(claimed);
        if (current.status() == Status.RESTORED) {
            return;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean marked = payments.markRestored(payment.id(), now);
        Instant restoredAt = marked ? now : requirePayment(claimed).restoredAt();
        if (restoredAt == null) {
            throw new IllegalStateException("payment restoration timestamp missing");
        }
        boolean workCompleted = workRepository.markRestored(claimed.claimId(), restoredAt);
        if (marked && !workCompleted) {
            throw new IllegalStateException("claimed restoration work was not completed");
        }
        if (marked && !fullyCanceled) {
            events.paymentRejected(payment);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean hold(OrderStockRestoration claimed, String code) {
        return workRepository.hold(claimed, claimed.hold(code), clock.instant());
    }

    private OrderStockRestoration currentWork(OrderStockRestoration claimed) {
        OrderStockRestoration work = workRepository.findByClaimId(claimed.claimId()).orElseThrow(Hold::ownershipMismatch);
        if (work.id() != claimed.id() || work.origin() != Origin.PAYMENT_RECORD || work.orderId() != claimed.orderId()
            || work.paymentId() != claimed.paymentId() || work.stockReservationId() != claimed.stockReservationId()) {
            throw Hold.ownershipMismatch();
        }
        return work;
    }

    private OrderPayment requirePayment(OrderStockRestoration work) {
        OrderPayment payment = payments.findByPaymentId(work.paymentId()).orElseThrow(Hold::ownershipMismatch);
        if (payment.paymentId() != work.paymentId() || payment.orderId() != work.orderId()
            || !Objects.equals(payment.restorationClaimId(), work.claimId())
            || !Objects.equals(payment.stockReservationId(), work.stockReservationId()) || !payment.result().restorableRejection()) {
            throw Hold.ownershipMismatch();
        }
        return payment;
    }

    private void requireRestorableOrder(Order order, OrderPayment payment) {
        if (order.memberId() != payment.memberId() || !order.quote().totalAmount().equals(payment.totalAmount())
            || (!order.isAwaitingPaymentApproval() && order.status() != OrderStatus.CANCELED)
            || payments.findPaidByOrderId(order.id()).isPresent()) {
            throw Hold.ownershipMismatch();
        }
    }

    private PaymentQueryResult queryPayment(OrderPayment record) {
        PaymentQueryResult payment = paymentQuery.findPayment(record.paymentId())
            .orElseThrow(() -> new IllegalStateException("payment source unavailable"));
        if (payment.paymentId() != record.paymentId() || payment.orderId() != record.orderId() || payment.memberId() != record.memberId()
            || !payment.amount().equals(record.totalAmount()) || approvalMismatch(record.approvedAt(), payment.approvedAt())) {
            throw Hold.ownershipMismatch();
        }
        String status = payment.statusCode();
        if (APPROVAL_REQUIRED.contains(status) ? payment.approvedAt() == null || payment.method() == null : !INCOMPLETE_STATES.contains(status)) {
            throw new IllegalStateException("payment source status or approval evidence unavailable");
        }
        return payment;
    }

    private static boolean approvalMismatch(Instant first, Instant second) {
        return first != null && second != null && !first.truncatedTo(ChronoUnit.MICROS).equals(second.truncatedTo(ChronoUnit.MICROS));
    }

    public static final class Hold extends RuntimeException {

        private final String code;

        private Hold(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }

        private static Hold ownershipMismatch() {
            return new Hold(OrderStockRestoration.OWNERSHIP_MISMATCH);
        }
    }
}
