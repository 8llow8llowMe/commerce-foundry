package com.commercefoundry.domainlayer.payment.application.service.processor;

import static com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason.*;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplyOutcome;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplySnapshot;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentReplyFailurePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentResultQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.TopicNames;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원천 조회는 트랜잭션 밖, 결제 변경과 inbox는 같은 짧은 트랜잭션이다. */
@Component
@RequiredArgsConstructor
public class OrderPaymentReplyProcessor {
    public static final String CONSUMER_PURPOSE = "order-payment-result";
    private final OrderPaymentResultQueryPort orderPaymentResultQueryPort;
    private final PaymentRepositoryPort paymentRepositoryPort;
    private final PaymentCancelPersistencePort cancelPort;
    private final ProcessedEventStore processedEventStore;
    private final TopicNames topicNames;
    private final Clock clock;
    private final OrderPaymentReplyFailurePort failures;
    private final PaymentCancelPolicy cancelPolicy;

    @Transactional(propagation = Propagation.NEVER)
    public OrderPaymentReplyException.Reason classifyFailure(RuntimeException failure) {
        return failure instanceof OrderPaymentReplyException reply ? reply.getReason() : failures.classify(failure);
    }

    /** 커밋이 끝난 Facade만 호출한다. 실제 PG 취소는 연결하지 않는다. */
    @Transactional(propagation = Propagation.NEVER)
    public void afterRejection(long paymentId) {
        cancelPolicy.onRejected(paymentId);
    }

    @Transactional(readOnly = true, timeout = 5)
    public boolean isProcessed(UUID eventId) {
        if (eventId == null) {
            throw new OrderPaymentReplyException(INVALID_COMMAND);
        }
        return processedEventStore.exists(group(), eventId);
    }

    /** 원격 대기 중 DB 트랜잭션을 보유하지 않도록 직접 호출도 거절한다. */
    @Transactional(propagation = Propagation.NEVER)
    public OrderPaymentReplySnapshot fetchSource(OrderPaymentReplyCommand command) {
        requireCommand(command);
        OrderPaymentResultQueryResult source;
        try {
            source = orderPaymentResultQueryPort.query(command.paymentId());
        } catch (OrderPaymentResultQueryException failure) {
            throw new OrderPaymentReplyException(switch (failure.getFailure()) {
                case NOT_READY -> SOURCE_NOT_READY;
                case UNAVAILABLE -> SOURCE_UNAVAILABLE;
                case UNAUTHORIZED -> SOURCE_UNAUTHORIZED;
                case REJECTED, CONTRACT_VIOLATION -> SOURCE_CONTRACT;
            });
        } catch (RuntimeException failure) {
            throw new OrderPaymentReplyException(SOURCE_UNAVAILABLE);
        }
        if (source == null || source.result() == null) {
            throw new OrderPaymentReplyException(SOURCE_CONTRACT);
        }
        boolean paid = source.result() == Result.PAID;
        var snapshot = new OrderPaymentReplySnapshot(source.paymentId(), source.orderId(), source.memberId(), paid,
            paid ? null : source.result().name().substring("REJECTED_".length()), source.amount(), source.approvedAt(), source.stockReservationId());
        validateSource(command, snapshot);
        return snapshot;
    }

    /** flush와 커밋 실패는 프록시 바깥 Facade에서 정제한다. 같은 트랜잭션 안에서 재시도하지 않는다. */
    @Transactional(timeout = 5)
    public OrderPaymentReplyOutcome record(OrderPaymentReplyCommand command, OrderPaymentReplySnapshot source) {
        validateSource(command, source);
        if (processedEventStore.exists(group(), command.eventId())) {
            return OrderPaymentReplyOutcome.alreadyProcessed();
        }
        Payment payment = paymentRepositoryPort.findById(command.paymentId())
            .orElseThrow(() -> new OrderPaymentReplyException(LOCAL_NOT_FOUND));
        if (payment.orderId() != source.orderId() || payment.memberId() != source.memberId() || !payment.amount().equals(source.amount())
            || source.approvedAt() != null && (payment.approvedAt() == null
                || !payment.approvedAt().truncatedTo(ChronoUnit.MICROS).equals(source.approvedAt().truncatedTo(ChronoUnit.MICROS)))) {
            throw new OrderPaymentReplyException(LOCAL_MISMATCH);
        }
        Payment next;
        try {
            next = source.paid() ? payment.markOrderSynced() : payment.markOrderSyncFailed(source.rejectionReason());
        } catch (PaymentTransitionException failure) {
            throw new OrderPaymentReplyException(INVALID_ORIGIN);
        }
        boolean changed = next != payment;
        if (changed) {
            paymentRepositoryPort.save(next);
        }
        processedEventStore.record(group(), command.eventId(), command.kind().eventType(), clock.instant());
        boolean rejection = changed && !source.paid();
        boolean candidate = rejection && payment.orderSyncFailureCode() == null && payment.status() != PaymentStatus.CANCELED;
        if (candidate) {
            Money canceled = cancelPort.findByPaymentId(payment.id()).stream().filter(c -> c.status() == PaymentCancel.Status.SUCCEEDED)
                .map(PaymentCancel::amount).reduce(Money.zero(payment.amount().currency()), Money::plus);
            candidate = payment.amount().isGreaterThan(canceled);
        }
        return new OrderPaymentReplyOutcome(false, changed, rejection, candidate);
    }

    private static void validateSource(OrderPaymentReplyCommand command, OrderPaymentReplySnapshot source) {
        requireCommand(command);
        if (source == null || source.amount() == null || source.amount().amount() <= 0
            || (source.paid() ? source.rejectionReason() != null
                : source.rejectionReason() == null || !OrderPaymentReplyCommand.REJECTION_REASONS.contains(source.rejectionReason()))
            || source.paymentId() <= 0 || source.orderId() <= 0 || source.memberId() <= 0
            || source.stockReservationId() != null && source.stockReservationId() <= 0
            || source.paid() && (source.approvedAt() == null || source.stockReservationId() == null)
            || !source.paid() && !"ORDER_CANCELED".equals(source.rejectionReason()) && source.stockReservationId() != null) {
            throw new OrderPaymentReplyException(SOURCE_CONTRACT);
        }
        boolean paid = source.paid();
        if (source.paymentId() != command.paymentId() || source.orderId() != command.orderId()
            || paid != (command.kind() == OrderPaymentReplyCommand.Kind.PAID)
            || !paid && !source.rejectionReason().equals(command.rejectionReason())) {
            throw new OrderPaymentReplyException(SOURCE_MISMATCH);
        }
    }

    private static void requireCommand(OrderPaymentReplyCommand command) {
        if (command == null) {
            throw new OrderPaymentReplyException(INVALID_COMMAND);
        }
    }

    private String group() {
        return topicNames.consumerGroup(CONSUMER_PURPOSE);
    }
}
