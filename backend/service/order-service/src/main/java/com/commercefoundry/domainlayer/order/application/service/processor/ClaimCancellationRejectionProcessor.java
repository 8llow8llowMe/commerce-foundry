package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderStatusHistoryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimAdminRecoveryPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 거절의 DB 단계 (#17 A4, {@code order-flow-guide.md} §5 "결제 후 취소 거절") — 자격 확인(tx1 {@link #prepare}, 읽기 전용 · 잠금 없음)과
 * 거절 기록(tx2 {@link #reject}, 주문 NOWAIT 잠금 · 재판정 · 저장)이다. 둘 사이의 payment 무환불 판정(void)은 부르는 쪽이 트랜잭션 밖에서
 * {@link ClaimCancellationVoidProcessor} 로 한 번 한다 — 이 Processor 는 원격을 부르지 않는다.
 *
 * <p>자격은 {@link ClaimAdminRecoveryPolicy#requireCancellationRejection}(R1 · R2 · R4~R7)이 정본이다. 읽는 순서는 클레임 없음({@code ORDER_032}) →
 * 화면 버전 불일치({@code ORDER_037}) → 클레임만 본 후보 R1 · R2 → 주문 R4 → 같은 주문의 클레임 목록 R5 → 결제 완료 기록 R7 → 상태 이력 최신 행 R6 이고,
 * 자격 미달은 모두 409 {@code ORDER_041} 이다. 클레임만으로 후보가 아니거나 주문이 취소 요청이 아니면 뒤를 읽지 않는다.
 *
 * <p>거절은 재고 복원 · 환불을 하지 않는다 — 재고 복원 포트를 주입받지 않고 payment 취소도 부르지 않는다. 주문은 정책이 이력에서 꺼낸 직전 상태로만 되돌린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimCancellationRejectionProcessor {

    private final ClaimRepositoryPort claimRepositoryPort;
    private final OrderRepositoryPort orderRepositoryPort;
    private final OrderPaymentRepositoryPort orderPaymentRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /** 어드민 상세의 "취소 거절" 표시 — 클레임만 본 후보(R1 · R2)다. 주문 · 이력 · 결제 기록과 payment 판정은 실행 때 본다(상세 조회는 void 를 부르지 않는다). */
    public boolean isRejectable(Claim claim) {
        return ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(claim);
    }

    /**
     * 자격 확인(tx1) — 아무것도 쓰지 않고 잠그지 않는다. 이 단계를 통과한 클레임에만 payment 무환불 판정을 부른다 — 결과 미확인({@code REFUND_UNCONFIRMED})
     * 클레임에 판정을 부르면 payment 가 그 클레임을 봉인해 다시 시도 경로가 막히기 때문이다.
     *
     * @param expectedVersion 운영자가 상세 화면에서 본 클레임 버전
     * @return 지금 저장된 클레임 — 이 값의 결제 아이디 · 주문 아이디 · 클레임 아이디 · 환불액으로 판정을 부른다
     * @throws OrderException 없음 {@code NOT_FOUND_CLAIM} · 버전 불일치 {@code CLAIM_CONCURRENTLY_CHANGED} · 자격 미달 {@code CLAIM_DECISION_NOT_ALLOWED}
     */
    @Transactional(readOnly = true, timeout = 5)
    public Claim prepare(long claimId, long expectedVersion) {
        Claim claim = loadCandidate(claimId, expectedVersion);
        Order order = orderRepositoryPort.findById(claim.orderId()).orElseThrow(() -> orderMissing(claim));
        requireEligible(claim, order);
        return claim;
    }

    /**
     * 거절 기록(tx2) — payment 가 무환불을 확정 · 봉인한 뒤에만 부른다. 클레임을 다시 읽어 버전 · 후보를 대조하고(tx1 결과에 기대지 않는다), <b>주문을 NOWAIT 으로
     * 잠근 뒤</b> R4~R7 을 다시 판정한다(정본은 이쪽이다). 잠금 순서와 방식은 반품 결정({@link ClaimReturnDecisionProcessor#reject})과 같다.
     * {@link Claim#rejectCancellation} 저장 → {@link Order#rejectCancellation}(정책이 돌려준 직전 상태) + {@code ADMIN} 이력(사유)이 한 커밋이다.
     *
     * @return 저장된 거절 클레임(올라간 버전)
     * @throws OrderException 없음 {@code NOT_FOUND_CLAIM} · 버전 불일치 · 주문 잠금 경합 · 저장 낙관락 {@code CLAIM_CONCURRENTLY_CHANGED} ·
     *     자격 미달 {@code CLAIM_DECISION_NOT_ALLOWED}
     */
    @Transactional(timeout = 5)
    public Claim reject(long claimId, long expectedVersion, String reason, long adminId) {
        Claim claim = loadCandidate(claimId, expectedVersion);
        Order order = lockOrder(claim);
        OrderStatus restoreTo = requireEligible(claim, order);
        Claim rejected = claim.rejectCancellation(new Claim.Decision(reason, adminId, clock.instant(), claim.failureCode()));
        Claim saved;
        Order restored;
        try {
            saved = claimRepositoryPort.update(rejected);
            restored = order.rejectCancellation(restoreTo);
            orderRepositoryPort.saveStatusChange(restored,
                OrderStatusHistory.changedByAdmin(snowflakeIdGenerator.generateId(), order, restored, adminId, reason));
        } catch (OptimisticLockingFailureException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException exception) {
            // 같은 클레임 · 주문을 잡은 다른 쓰기(환불 기록의 잠금 TX 등)와 겹쳐 DB 가 잠금 대기 · 교착으로 이쪽을 끊으면 500 이 아니라 같은 동시 변경으로 답한다
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        log.warn("cancellation rejected by admin after no-refund confirmation. claimId={}, orderId={}, paymentId={}, previousFailureCode={}, orderStatus={}, "
            + "adminId={}", claim.id(), order.id(), claim.paymentId(), claim.failureCode(), restored.status(), adminId);
        return saved;
    }

    /** 클레임 없음 → 버전 → 클레임만 본 후보(R1 · R2). 후보가 아니면 주문을 읽거나 잠그지 않는다 — 잠금은 실제로 바꿀 요청만 잡는다. */
    private Claim loadCandidate(long claimId, long expectedVersion) {
        Claim claim = claimRepositoryPort.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
        if (!Objects.equals(claim.version(), expectedVersion)) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        if (!ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(claim)) {
            log.warn("cancellation rejection refused, claim is not a rejected refund. claimId={}, type={}, status={}, failureCode={}, nextAttemptAt={}",
                claim.id(), claim.type(), claim.status(), claim.failureCode(), claim.nextAttemptAt());
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        return claim;
    }

    private Order lockOrder(Claim claim) {
        try {
            return orderRepositoryPort.findByIdForUpdate(claim.orderId()).orElseThrow(() -> orderMissing(claim));
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException exception) {
            // 같은 주문의 고객 요청 · 환불 기록 · 다른 운영자가 잡고 있다 — 기다리지 않고 화면을 다시 읽게 한다
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
    }

    /** 저장된 취소 클레임의 주문이 없다 — 데이터 이상이라 error 로 남기고 거절하지 않는다. */
    private static OrderException orderMissing(Claim claim) {
        log.error("cancellation rejection refused, claim has no order. claimId={}, orderId={}", claim.id(), claim.orderId());
        return new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
    }

    /**
     * R4 → R5 · R7 · R6 판정. 주문이 취소 요청이 아니면 클레임 목록 · 결제 기록 · 이력을 읽지 않는다. 나머지는 읽은 값을 정책에 한 번에 넘긴다 — 정책이 정본이다.
     * 이력은 {@link OrderRepositoryPort#findHistories}(아이디 오름차순)의 마지막 행이다.
     *
     * @return 되돌릴 주문 상태(정책 반환값) — 그대로 {@link Order#rejectCancellation} 에 넘긴다
     */
    private OrderStatus requireEligible(Claim claim, Order order) {
        if (order.status() != OrderStatus.CANCEL_REQUESTED) {
            log.warn("cancellation rejection refused, order is not cancel requested. claimId={}, orderId={}, orderStatus={}", claim.id(), order.id(),
                order.status());
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        List<Claim> orderClaims = claimRepositoryPort.findAllByOrderId(order.id());
        Optional<OrderPayment> payment = orderPaymentRepositoryPort.findPaidByOrderId(order.id());
        if (payment.isEmpty()) {
            // 결제 후 취소 클레임이 있는데 결제 완료 기록이 없다 — 접수 때 대조했으므로 데이터 이상이다
            log.error("cancellation rejection refused, order has no payment record. claimId={}, orderId={}", claim.id(), order.id());
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        List<OrderStatusHistoryQueryResult> histories = orderRepositoryPort.findHistories(order.id());
        OrderStatusHistoryQueryResult latest = histories.isEmpty() ? null : histories.getLast();
        OrderStatus latestFrom = latest == null ? null : latest.fromStatus();
        OrderStatus latestTo = latest == null ? null : latest.toStatus();
        try {
            return ClaimAdminRecoveryPolicy.requireCancellationRejection(claim, order, orderClaims, latestFrom, latestTo, payment.get().paymentId());
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            // 다른 열린 취소 · 되돌릴 상태를 이력에서 확인하지 못함 · 결제 기록과 클레임의 결제가 다름 — 금액 · 사유는 남기지 않는다
            log.warn("cancellation rejection refused. claimId={}, orderId={}, latestHistory={} -> {}, error={}", claim.id(), order.id(), latestFrom,
                latestTo, invalid.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
    }
}
