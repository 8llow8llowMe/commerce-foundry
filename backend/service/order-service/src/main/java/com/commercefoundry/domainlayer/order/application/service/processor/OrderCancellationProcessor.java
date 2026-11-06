package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.CancellationStepOutcome;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 고객 취소의 DB 단계 (#16 PR-2, {@code order-flow-guide.md} §5) — 접수(tx1) · 환불 결과 반영(tx2) · 재고 복원 결과 반영(tx3) · 재시도 선점 · 실패 기록.
 * 원격 호출(환불 · 복원)은 이 사이에 트랜잭션 밖에서 한다({@link OrderCancellationPipelineProcessor}). 각 단계는 짧은 트랜잭션(5초)이고 클레임 · 주문의
 * {@code @Version} 으로 진 쪽을 가른다 — 넘겨받은 클레임의 버전이 낡았으면 저장에서 {@code OptimisticLockingFailureException} 이다(다른 워커가 이미 처리했다).
 *
 * <p>종결 실패(REFUND_FAILED · RESTORATION_FAILED)는 운영자가 어드민 클레임 목록으로 확인한다 — 저장할 때마다 클레임 · 주문 · 결제 아이디 · 실패 코드를
 * error 로그로 남긴다(금액은 남기지 않는다). 수습은 어드민 클레임 API(#17 PR-A, {@code ClaimAdminActionProcessor})다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancellationProcessor {

    /** 환불은 확정됐는데 주문이 취소 요청 · 취소 완료가 아니다(데이터 이상) — 재고 복원을 하지 않고 운영자에게 넘긴다. */
    public static final String ORDER_STATUS_UNEXPECTED = "ORDER_STATUS_UNEXPECTED";

    private final OrderRepositoryPort orderRepositoryPort;
    private final ClaimRepositoryPort claimRepositoryPort;
    private final OrderPaymentRepositoryPort orderPaymentRepositoryPort;
    private final OrderCancellationProperties properties;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /**
     * 접수(tx1) — 주문 행을 잠그고 소유 · 상태를 본 뒤 환불 대기 클레임 · 취소 요청 상태 · 고객 이력을 한 커밋으로 남긴다. 다음 시도 시각은 {@code now + lease} 라
     * 이 요청이 바로 환불을 이어 가는 동안 재시도 스케줄러가 집지 않는다.
     *
     * <p>이미 취소 요청 · 취소 완료이고 이 주문의 취소 클레임이 있으면 아무것도 쓰지 않고 재생({@code created = false})이다 — 결제 후 취소가 닫혀 있어도 재생은 된다.
     *
     * @throws OrderException 없거나 남의 주문 {@code NOT_FOUND_ORDER} · 취소할 수 없는 상태 {@code ORDER_STATUS_TRANSITION_INVALID} ·
     *     운영자가 이 주문의 취소를 거절함 {@code CANCELLATION_REJECTED}(#17) ·
     *     결제 후 취소가 닫힘 {@code CANCELLATION_UNAVAILABLE} · 결제 반영 기록 없음 {@code CANCELLATION_PAYMENT_MISSING} ·
     *     환불액과 승인 금액 불일치 {@code CANCELLATION_AMOUNT_MISMATCH}
     */
    @Transactional(timeout = 5)
    public Requested request(long memberId, long orderId, OrderCancelReason reason) {
        Order order = orderRepositoryPort.findByIdAndMemberIdForUpdate(orderId, memberId)
            .orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        if (order.status() == OrderStatus.CANCEL_REQUESTED || order.status() == OrderStatus.CANCELED) {
            Claim existing = findCancellationClaim(orderId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName()));
            return new Requested(false, existing);
        }
        // 결제 대기는 결제 전 취소 경로다 — 이 흐름에 들어왔다면 판정 뒤 상태가 바뀐 것이라 거절한다
        if (order.status() == OrderStatus.PENDING_PAYMENT || !order.isCancelableByCustomer(properties.allowWhileMaking())) {
            throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName());
        }
        // 운영자가 이 주문의 취소를 거절했다(#17) — 같은 결정적 키로 다시 접수할 수 없고, 다시 받을지는 고객센터가 정한다
        if (!claimRepositoryPort.findRejectedCancellationOrderIds(List.of(orderId)).isEmpty()) {
            throw new OrderException(OrderErrorCode.CANCELLATION_REJECTED);
        }
        // 재시도 스케줄러가 꺼진 채 받으면 미확정 환불 · 복원이 영영 따라오지 않는다 — 접수 전에 닫는다
        if (!properties.paidCancellationOpen()) {
            throw new OrderException(OrderErrorCode.CANCELLATION_UNAVAILABLE);
        }
        OrderPayment payment = orderPaymentRepositoryPort.findPaidByOrderId(orderId).orElseThrow(() -> {
            log.error("paid order has no payment record for cancellation. orderId={}, status={}", orderId, order.status());
            return new OrderException(OrderErrorCode.CANCELLATION_PAYMENT_MISSING);
        });
        Money refund = CancellationRefundPolicy.fullRefund(order);
        if (!refund.equals(payment.totalAmount())) {
            // 금액은 로그에 남기지 않는다 — 주문 · 결제 아이디로 운영자가 대조한다
            log.error("cancellation refund differs from approved payment amount. orderId={}, paymentId={}", orderId, payment.paymentId());
            throw new OrderException(OrderErrorCode.CANCELLATION_AMOUNT_MISMATCH);
        }
        Claim claim = Claim.requestCancellation(snowflakeIdGenerator.generateId(), order, payment.paymentId(), refund, reason,
            snowflakeIdGenerator::generateId, clock.instant(), properties.retry().lease());
        orderRepositoryPort.saveStatusChange(order.requestCancellation(),
            OrderStatusHistory.cancellationRequestedByCustomer(snowflakeIdGenerator.generateId(), order, reason));
        return new Requested(true, claimRepositoryPort.insert(claim));
    }

    /**
     * 접수에서 진 요청의 재생 판정 — 회원의 주문이 취소 요청 · 취소 완료이고 취소 클레임이 있으면 그 주문이다. 아니면 빈 값이다.
     *
     * @throws OrderException 없거나 남의 주문 {@code NOT_FOUND_ORDER}
     */
    @Transactional(readOnly = true, timeout = 5)
    public Optional<Order> findReplay(long memberId, long orderId) {
        Order order = orderRepositoryPort.findByIdAndMemberId(orderId, memberId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        if (order.status() != OrderStatus.CANCEL_REQUESTED && order.status() != OrderStatus.CANCELED) {
            return Optional.empty();
        }
        return findCancellationClaim(orderId).map(claim -> order);
    }

    /** 주어진 주문 중 운영자가 결제 후 취소를 거절한 주문 (#17 PR-A) — 고객 응답의 {@code cancelable} 을 끈다. 빈 입력이면 조회하지 않는다. */
    @Transactional(readOnly = true, timeout = 5)
    public Set<Long> findRejectedOrderIds(Collection<Long> orderIds) {
        return claimRepositoryPort.findRejectedCancellationOrderIds(orderIds);
    }

    /** 주문의 결제 후 취소 클레임(지금 저장된 값 · 버전). 없으면 빈 값이다. */
    @Transactional(readOnly = true, timeout = 5)
    public Optional<Claim> findClaim(long orderId) {
        return findCancellationClaim(orderId);
    }

    /**
     * 환불 결과 반영(tx2).
     *
     * <ul>
     *   <li>DONE — 주문이 취소 요청이면 클레임 REFUNDED(다음 시각 {@code now + lease}, 이어서 재고를 복원한다) + 주문 취소 완료 + {@code SERVICE} 이력.
     *       이미 취소 완료면 클레임만. 주문이 그 밖의 상태(데이터 이상)면 복원하지 않고 RESTORATION_FAILED({@link #ORDER_STATUS_UNEXPECTED}).
     *       미확정으로 닫힌 클레임({@link Claim#REFUND_UNCONFIRMED})의 늦은 성공도 받는다</li>
     *   <li>FAILED — 클레임 REFUND_FAILED(원격 코드). 주문은 취소 요청 그대로 운영자에게 넘긴다</li>
     *   <li>RETRY — 재시도 상한 안이면 다음 시각을 미루고, 넘었으면 REFUND_FAILED({@link Claim#REFUND_UNCONFIRMED})</li>
     * </ul>
     *
     * @param claim 환불을 보낸 클레임 — 그 뒤 다른 쪽이 바꿨으면 저장에서 낙관적 락 예외다
     * @return 저장된 클레임(올라간 버전)
     */
    @Transactional(timeout = 5)
    public Claim recordRefund(Claim claim, CancellationStepOutcome outcome) {
        if (outcome.kind() == CancellationStepOutcome.Kind.DONE) {
            return applyRefunded(claim);
        }
        if (claim.status() != Claim.Status.REQUESTED) {
            // 늦게 온 미확정 · 거절은 이미 닫힌 클레임을 바꾸지 않는다
            log.warn("refund outcome ignored for non-requested claim. claimId={}, status={}, outcome={}", claim.id(), claim.status(), outcome.kind());
            return claim;
        }
        if (outcome.kind() == CancellationStepOutcome.Kind.FAILED) {
            return saveFailure(claim.failRefund(outcome.failureCode()));
        }
        return retryOrGiveUp(claim);
    }

    /**
     * 재고 복원 결과 반영(tx3) — DONE 이면 COMPLETED, FAILED 면 RESTORATION_FAILED(원격 · 데이터 코드), RETRY 면 상한 안에서 다음 시각을 미루고 넘었으면
     * RESTORATION_FAILED({@link Claim#RESTORATION_UNCONFIRMED}). 주문은 이미 취소 완료라 바꾸지 않는다.
     *
     * @return 저장된 클레임(올라간 버전)
     */
    @Transactional(timeout = 5)
    public Claim recordRestoration(Claim claim, CancellationStepOutcome outcome) {
        return switch (outcome.kind()) {
            case DONE -> claimRepositoryPort.update(claim.complete());
            case FAILED -> saveFailure(claim.failRestoration(outcome.failureCode()));
            case RETRY -> retryOrGiveUp(claim);
        };
    }

    /**
     * 원격 결과를 반영하지 못하고 끝난 시도(낙관적 락이 아닌 예외 — DB 장애 · 버그)를 미확정 한 번으로 센다. 지금 저장된 클레임을 다시 읽어 대기 상태면
     * 그 단계의 재시도(상한을 넘으면 *_UNCONFIRMED 종결)로 저장한다 — 같은 실패가 횟수 없이 영영 반복되지 않게. 이미 종결이면 아무것도 하지 않는다.
     */
    @Transactional(timeout = 5)
    public void recordAttemptFailure(long orderId) {
        findCancellationClaim(orderId).filter(claim -> claim.status().isAwaitingAttempt()).ifPresent(this::retryOrGiveUp);
    }

    /** 재시도 대상 — 환불 대기 · 복원 대기이고 다음 시각이 지난 취소 클레임을 다음 시각 순으로 {@code batch-size} 건까지. */
    @Transactional(readOnly = true, timeout = 5)
    public List<Claim> findRetryTargets() {
        return claimRepositoryPort.findCancellationRetryTargets(clock.instant(), properties.retry().batchSize());
    }

    /**
     * 재시도 선점 — 원격 호출 전에 다음 시각을 {@code now + lease} 로 미뤄 먼저 커밋한다. 다른 워커가 먼저 바꿨으면 낙관적 락 예외로 진다(그 행은 건너뛴다).
     *
     * @return 선점한 클레임(올라간 버전) — 이 값으로 원격 호출 · 결과 반영을 이어 간다
     */
    @Transactional(timeout = 5)
    public Claim hold(Claim claim) {
        return claimRepositoryPort.update(claim.holdUntil(clock.instant().plus(properties.retry().lease())));
    }

    /** 재고 복원에 쓸 주문(선점 아이디 · 주문 스냅숏). 없으면 데이터 이상이다. */
    @Transactional(readOnly = true, timeout = 5)
    public Order getOrder(long orderId) {
        return loadOrder(orderId);
    }

    private Order loadOrder(long orderId) {
        return orderRepositoryPort.findById(orderId).orElseThrow(() -> new IllegalStateException("claim order not found: orderId=" + orderId));
    }

    private Optional<Claim> findCancellationClaim(long orderId) {
        return claimRepositoryPort.findByOrderIdAndRequestKey(orderId, Claim.cancellationRequestKey(orderId))
            .filter(claim -> claim.type() == Claim.Type.CANCELLATION);
    }

    /** 환불 확정 반영 — 주문 상태에 따라 취소 완료로 바꾸거나(취소 요청), 클레임만 바꾸거나(이미 취소 완료), 데이터 이상으로 닫는다. */
    private Claim applyRefunded(Claim claim) {
        Instant now = clock.instant();
        Claim refunded = claim.markRefunded(now.plus(properties.retry().lease()));
        Order order = loadOrder(claim.orderId());
        if (order.status() == OrderStatus.CANCEL_REQUESTED) {
            Claim saved = claimRepositoryPort.update(refunded);
            orderRepositoryPort.saveStatusChange(order.completeCancellation(), OrderStatusHistory.cancellationRefunded(snowflakeIdGenerator.generateId(), order));
            return saved;
        }
        if (order.status() == OrderStatus.CANCELED) {
            return claimRepositoryPort.update(refunded);
        }
        // 환불은 이미 나갔다 — 재고는 건드리지 않고 클레임을 종결 실패로 닫아 운영자에게 넘긴다
        log.error("cancellation refunded but order status unexpected, restoration skipped. claimId={}, orderId={}, paymentId={}, status={}",
            claim.id(), claim.orderId(), claim.paymentId(), order.status());
        return saveFailure(refunded.failRestoration(ORDER_STATUS_UNEXPECTED));
    }

    /** 미확정 — 상한 안이면 다음 시각을 미루고, 넘었으면 그 단계의 미확정 코드(환불 {@code REFUND_UNCONFIRMED} · 복원 {@code RESTORATION_UNCONFIRMED})로 종결한다. */
    private Claim retryOrGiveUp(Claim claim) {
        OrderCancellationProperties.Retry retry = properties.retry();
        if (claim.attemptCount() >= retry.maxAttempts()) {
            return saveFailure(claim.status() == Claim.Status.REQUESTED
                ? claim.failRefund(Claim.REFUND_UNCONFIRMED) : claim.failRestoration(Claim.RESTORATION_UNCONFIRMED));
        }
        return claimRepositoryPort.update(claim.retryLater(clock.instant(), retry.initialBackoff(), retry.maxBackoff()));
    }

    /** 종결 실패 저장 — 운영자가 찾을 수 있게 아이디 · 코드를 error 로 남긴다(금액 제외). */
    private Claim saveFailure(Claim failed) {
        Claim saved = claimRepositoryPort.update(failed);
        log.error("cancellation claim failed, operator action required. claimId={}, orderId={}, paymentId={}, status={}, failureCode={}, attempts={}",
            saved.id(), saved.orderId(), saved.paymentId(), saved.status(), saved.failureCode(), saved.attemptCount());
        return saved;
    }

    /**
     * 접수 결과.
     *
     * @param created 이번 요청이 접수했는가 — false 면 이미 있던 취소 클레임의 재생이다(원격 단계를 다시 돌리지 않는다)
     */
    public record Requested(boolean created, Claim claim) {
    }
}
