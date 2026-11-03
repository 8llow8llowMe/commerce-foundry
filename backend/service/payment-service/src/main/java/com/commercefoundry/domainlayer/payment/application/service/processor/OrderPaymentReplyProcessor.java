package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyRetryException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReply;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplyOutcome;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentReplyMetricsPort;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentResultQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.TopicNames;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 결제 반영 회신({@code order-paid} · {@code order-payment-rejected})을 결제의 주문 반영 기록에 남긴다 (#308 PR-C, {@code payment-guide.md} §6).
 *
 * <p>봉투 본문은 서명이 없어 알림으로만 본다 — order 원천 조회({@link OrderPaymentResultQueryPort})로 결과를 다시 확정하고, 로컬 결제와 회원 · 금액 · 승인 시각을 대조한다.
 * 원천 조회는 트랜잭션 밖({@link #fetchSource}), 반영 기록과 inbox 는 한 트랜잭션({@link #record}), 취소 정책 훅은 커밋 뒤({@link #requestCancel})다.
 *
 * <p>실패 분류 — 다시 하면 바뀔 수 있으면 {@link OrderPaymentReplyRetryException}(컨슈머 재시도 → 상한 뒤 DLT), 다시 해도 같으면 {@link NonRetryableEventException}(바로 DLT).
 * <ul>
 *   <li>재시도 — 원천 미반영(404 {@code ORDER_028}) · 조회 장애 · 서비스 인증 실패, 저장 버전 충돌</li>
 *   <li>DLT — 원천 조회 4xx 거절 · 응답 계약 위반, 이벤트와 원천의 결제 · 주문 · 결과 불일치(원천은 최초 결과로 고정돼 다시 해도 같다 — order 는 기록과 회신 outbox 를
 *       한 트랜잭션에 쓰므로 원천이 아직 없는 경우는 404 로 따로 온다), 로컬 결제 없음, 원천과 로컬 결제의 주문 · 회원 · 금액 · 승인 시각 불일치, 회신을 받을 수 없는 결제 상태</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentReplyProcessor {

    /** 컨슈머 그룹 목적 — {@code <ns>.payment.order-payment-result}. */
    public static final String CONSUMER_PURPOSE = "order-payment-result";
    private static final String REJECTED_PREFIX = "REJECTED_";

    private final OrderPaymentResultQueryPort orderPaymentResultQueryPort;
    private final PaymentRepositoryPort paymentRepositoryPort;
    private final PaymentCancelPolicy paymentCancelPolicy;
    private final OrderPaymentReplyMetricsPort orderPaymentReplyMetricsPort;
    private final ProcessedEventStore processedEventStore;
    private final TopicNames topicNames;
    private final Clock clock;

    @Transactional(readOnly = true, timeout = 10)
    public boolean isProcessed(UUID eventId) {
        return processedEventStore.exists(group(), eventId);
    }

    /**
     * order 원천을 조회해 회신과 맞는지 본다. 원격 호출이라 트랜잭션을 걸지 않는다 — 호출자도 트랜잭션 밖이어야 한다.
     *
     * @throws OrderPaymentReplyRetryException 원천 미반영 · 조회 장애 · 서비스 인증 실패
     * @throws NonRetryableEventException 원천 조회 거절(4xx) · 응답 계약 위반 · 이벤트와 원천 불일치
     */
    public OrderPaymentReply fetchSource(OrderPaymentReplyCommand command) {
        OrderPaymentResultQueryResult source;
        try {
            source = orderPaymentResultQueryPort.query(command.paymentId());
        } catch (OrderPaymentResultQueryException exception) {
            throw switch (exception.getFailure()) {
                case NOT_READY -> new OrderPaymentReplyRetryException(OrderPaymentReplyRetryException.Reason.SOURCE_NOT_READY);
                case UNAVAILABLE -> new OrderPaymentReplyRetryException(OrderPaymentReplyRetryException.Reason.SOURCE_UNAVAILABLE);
                case UNAUTHORIZED -> new OrderPaymentReplyRetryException(OrderPaymentReplyRetryException.Reason.SOURCE_UNAUTHORIZED);
                case REJECTED, CONTRACT_VIOLATION -> new NonRetryableEventException("order payment result unusable: " + exception.getFailure().name());
            };
        }
        if (source.paymentId() != command.paymentId() || source.orderId() != command.orderId() || !sameKind(command, source.result())) {
            log.error("order payment reply differs from source. eventId={}, paymentId={}, kind={}, sourceResult={}",
                command.eventId(), command.paymentId(), command.kind(), source.result());
            orderPaymentReplyMetricsPort.recordSourceMismatch();
            throw new NonRetryableEventException("order payment reply differs from source");
        }
        boolean paid = source.result() == Result.PAID;
        return new OrderPaymentReply(source.paymentId(), source.orderId(), source.memberId(), source.amount(), source.approvedAt(), paid,
            paid ? null : rejectionReason(source.result()));
    }

    /**
     * 반영 기록 → inbox 를 한 트랜잭션으로 쓴다. 같은 이벤트가 동시에 들어와 inbox 유니크 키가 부딪히면 이 트랜잭션이 롤백되고, 재전달에서 {@link #isProcessed} 로 걸러진다.
     * 같은 회신의 재수신(다른 eventId)은 도메인 멱등(같은 인스턴스)이라 저장하지 않는다 — inbox 만 남는다.
     *
     * @throws NonRetryableEventException 로컬 결제 없음 · 원천과 불일치 · 회신을 받을 수 없는 결제 상태(늦은 승인 등)
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException 버전 충돌(동시 취소 등) — 재시도에서 다시 읽는다
     */
    @Transactional(timeout = 10)
    public OrderPaymentReplyOutcome record(OrderPaymentReplyCommand command, OrderPaymentReply reply) {
        if (processedEventStore.exists(group(), command.eventId())) {
            return OrderPaymentReplyOutcome.noCancel(reply.paymentId(), reply.orderId());
        }
        Payment payment = paymentRepositoryPort.findById(reply.paymentId())
            .orElseThrow(() -> new NonRetryableEventException("payment not found for order reply"));
        if (!matchesLocal(payment, reply)) {
            log.error("order payment reply does not match local payment. eventId={}, paymentId={}", command.eventId(), payment.id());
            throw new NonRetryableEventException("order payment reply does not match local payment");
        }
        Payment next;
        try {
            next = reply.paid() ? payment.markOrderSynced() : payment.markOrderSyncFailed(reply.rejectionReason());
        } catch (PaymentTransitionException exception) {
            log.error("order payment reply for payment that never reported to order. eventId={}, paymentId={}, status={}",
                command.eventId(), payment.id(), payment.status());
            throw new NonRetryableEventException("payment cannot record order reply. status=" + payment.status().name());
        }
        boolean changed = next != payment;
        if (changed) {
            paymentRepositoryPort.save(next);
        }
        processedEventStore.record(group(), command.eventId(), command.kind().eventType(), clock.instant());
        if (reply.paid()) {
            return OrderPaymentReplyOutcome.noCancel(payment.id(), payment.orderId());
        }
        if (!changed) {
            // 이미 같은 거절을 기록했거나(재전송) 반영 성공 뒤 늦게 온 거절이다 — 훅을 다시 부르지 않는다
            log.warn("order payment rejection already settled. eventId={}, paymentId={}, orderSynced={}", command.eventId(), payment.id(), payment.orderSynced());
            return OrderPaymentReplyOutcome.noCancel(payment.id(), payment.orderId());
        }
        if (next.status() == PaymentStatus.CANCELED) {
            // 이미 전액 취소된 결제 — 거절은 기록만 하고 취소를 다시 요청하지 않는다(멱등)
            log.info("order payment rejection recorded for fully canceled payment. eventId={}, paymentId={}, reason={}",
                command.eventId(), payment.id(), reply.rejectionReason());
            return OrderPaymentReplyOutcome.noCancel(payment.id(), payment.orderId());
        }
        return new OrderPaymentReplyOutcome(payment.id(), payment.orderId(), true, reply.rejectionReason());
    }

    /**
     * 커밋 뒤 취소 정책 훅을 부른다. 훅의 실패는 기록을 되돌리지 않는다 — 로그만 남기고, 놓친 건은 거절 결제 게이지로 찾는다.
     */
    public void requestCancel(OrderPaymentReplyOutcome outcome) {
        if (!outcome.cancelRequested()) {
            return;
        }
        try {
            paymentCancelPolicy.onOrderRejected(outcome.paymentId(), outcome.orderId(), outcome.rejectionReason());
        } catch (RuntimeException exception) {
            log.error("payment cancel policy failed. paymentId={}, orderId={}, reason={}, error={}",
                outcome.paymentId(), outcome.orderId(), outcome.rejectionReason(), exception.getClass().getSimpleName());
        }
    }

    private static boolean sameKind(OrderPaymentReplyCommand command, Result result) {
        return command.kind() == OrderPaymentReplyCommand.Kind.PAID
            ? result == Result.PAID
            : result != Result.PAID && rejectionReason(result).equals(command.rejectionReason());
    }

    /** {@code REJECTED_ORDER_CANCELED} → {@code ORDER_CANCELED} — order 회신 사유와 일대일이다. */
    private static String rejectionReason(Result result) {
        return result.name().substring(REJECTED_PREFIX.length());
    }

    /** 원천은 order 가 payment 를 재조회해 저장한 값이다 — 로컬 결제와 달라질 수 없다. 승인 시각은 저장 정밀도(마이크로초)로 본다. 거절의 승인 시각은 없을 수 있다. */
    private static boolean matchesLocal(Payment payment, OrderPaymentReply reply) {
        return payment.orderId() == reply.orderId()
            && payment.memberId() == reply.memberId()
            && payment.amount().equals(reply.amount())
            && (reply.approvedAt() == null || payment.approvedAt() != null && micros(payment.approvedAt()).equals(micros(reply.approvedAt())));
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private String group() {
        return topicNames.consumerGroup(CONSUMER_PURPOSE);
    }
}
