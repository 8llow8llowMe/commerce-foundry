package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderStatusHistoryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 클레임의 운영자 수습 DB 단계 (#17 PR-A, {@code order-flow-guide.md} §5) — 재시도 준비 · 거절 · 수동 환불 확정 · 복원 생략 종결.
 * 원격 단계(환불 · 재고 복원)는 이 트랜잭션 밖에서 {@link OrderCancellationPipelineProcessor} 가 잇는다.
 *
 * <p>멱등은 헤더 없이 상태 전제로 가른다 — 같은 결과가 이미 났으면 아무것도 쓰지 않고 {@code changed = false}, 모순이면 {@code CLAIM_STATUS_INVALID}(409).
 * 클레임 · 주문 모두 {@code @Version} 으로 진 쪽을 가른다 — 읽은 뒤 스케줄러 · 고객 · 다른 운영자가 먼저 커밋했으면 저장에서
 * {@code OptimisticLockingFailureException} 이고 이 트랜잭션은 롤백된다(주문 행 비관 잠금은 쓰지 않는다 — 두 행의 버전 비교로 충분하고, 고객 접수만 주문 행을 잠근다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimAdminActionProcessor {

    /** 결제 뒤 출고 전 상태 — 취소를 거절하면 돌아갈 수 있는 상태다({@link Order#rejectCancellation}). */
    private static final Set<OrderStatus> PAID_BEFORE_SHIPMENT = EnumSet.of(OrderStatus.PAID, OrderStatus.MAKING, OrderStatus.PREPARING);

    private final ClaimRepositoryPort claimRepositoryPort;
    private final OrderRepositoryPort orderRepositoryPort;
    private final OrderCancellationProperties properties;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /** 클레임 하나(지금 저장된 값 · 버전). 없으면 {@code NOT_FOUND_CLAIM}(404). */
    @Transactional(readOnly = true, timeout = 5)
    public Claim get(long claimId) {
        return load(claimId);
    }

    /**
     * 재시도 준비 — 종결 실패면 그 단계의 대기 상태로 다시 열고({@link Claim#reopen}), 대기 상태면 선점한다({@link Claim#holdUntil}). 둘 다 다음 시각을
     * {@code now + lease} 로 미뤄 먼저 커밋한다 — 이긴 쪽만 원격을 다시 보낸다(스케줄러의 선점과 같은 규칙). 이미 완료면 아무것도 쓰지 않는다.
     *
     * <p>환불은 확정됐는데 주문 상태가 이상해 닫은 클레임({@link OrderCancellationProcessor#ORDER_STATUS_UNEXPECTED})은 다시 열지 않는다 — 재고를 복원하면
     * 출고된 주문의 재고가 늘 수 있다. 운영자가 주문을 확인한 뒤 복원 생략 종결({@link #complete})로 닫는다.
     *
     * @throws OrderException 결제 후 취소가 닫힘(재시도 스케줄러 꺼짐) {@code CANCELLATION_UNAVAILABLE} · 없는 클레임 {@code NOT_FOUND_CLAIM} ·
     *     거절 · 반품 · 주문 상태 이상 {@code CLAIM_STATUS_INVALID}
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 스케줄러 · 고객 요청 · 다른 운영자가 먼저 바꿨을 때
     */
    @Transactional(timeout = 5)
    public Prepared prepareRetry(long claimId) {
        // 재시도 스케줄러가 꺼진 채 다시 열면 미확정 결과를 따라갈 쪽이 없다 — 고객 접수(ORDER_035)와 같은 게이트다
        if (!properties.paidCancellationOpen()) {
            throw new OrderException(OrderErrorCode.CANCELLATION_UNAVAILABLE);
        }
        Claim claim = loadCancellation(claimId);
        Instant until = clock.instant().plus(properties.retry().lease());
        return switch (claim.status()) {
            case COMPLETED -> new Prepared(false, claim);
            case REQUESTED, REFUNDED -> new Prepared(true, claimRepositoryPort.update(claim.holdUntil(until)));
            case REFUND_FAILED, RESTORATION_FAILED -> {
                if (OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED.equals(claim.failureCode())) {
                    throw invalid(claim);
                }
                Claim reopened = claimRepositoryPort.update(claim.reopen(until));
                log.warn("cancellation claim reopened by admin. claimId={}, orderId={}, from={}, failureCode={}", claim.id(), claim.orderId(), claim.status(),
                    claim.failureCode());
                yield new Prepared(true, reopened);
            }
            default -> throw invalid(claim);
        };
    }

    /**
     * 취소 거절 — 환불이 종결 거절된(REFUND_FAILED, 미확정 아님) 취소를 거절하고, 주문을 취소 요청 전 상태로 되돌린다. 클레임 REJECTED(결정 칸) · 주문 상태 ·
     * {@code ADMIN} 이력(사유)이 한 커밋이다. 되돌릴 상태는 상태 이력에서 취소 요청으로 바뀐 마지막 줄의 이전 상태다.
     *
     * @return 거절했으면 true, 이미 거절돼 있었으면 false(아무것도 쓰지 않는다)
     * @throws OrderException 없는 클레임 {@code NOT_FOUND_CLAIM} · 환불 실패가 아닌 상태 · 반품 {@code CLAIM_STATUS_INVALID} ·
     *     미확정 실패 {@code REFUND_UNVERIFIED} · 주문이 취소 요청이 아님 {@code ORDER_STATUS_TRANSITION_INVALID} · 되돌릴 상태 없음 {@code CANCELLATION_ORIGIN_NOT_FOUND}
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳이 클레임 · 주문을 먼저 바꿨을 때
     */
    @Transactional(timeout = 5)
    public boolean reject(long claimId, String reason, long adminId) {
        Claim claim = loadCancellation(claimId);
        if (claim.status() == Claim.Status.REJECTED) {
            return false;
        }
        if (claim.status() != Claim.Status.REFUND_FAILED) {
            throw invalid(claim);
        }
        if (claim.isRefundUnconfirmed()) {
            throw new OrderException(OrderErrorCode.REFUND_UNVERIFIED);
        }
        Order order = loadOrder(claim.orderId());
        if (order.status() != OrderStatus.CANCEL_REQUESTED) {
            log.error("refund-failed cancellation claim but order is not cancel-requested. claimId={}, orderId={}, status={}", claim.id(), order.id(),
                order.status());
            throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName());
        }
        OrderStatus restored = statusBeforeCancellation(order.id()).orElseThrow(() -> {
            log.error("status before cancellation not found in history, rejection aborted. claimId={}, orderId={}", claim.id(), order.id());
            return new OrderException(OrderErrorCode.CANCELLATION_ORIGIN_NOT_FOUND);
        });
        Order reverted = order.rejectCancellation(restored);
        claimRepositoryPort.update(claim.reject(decision(reason, adminId)));
        orderRepositoryPort.saveStatusChange(reverted, OrderStatusHistory.changedByAdmin(snowflakeIdGenerator.generateId(), order, reverted, adminId, reason));
        log.warn("cancellation claim rejected by admin. claimId={}, orderId={}, paymentId={}, failureCode={}, restored={}, adminId={}", claim.id(),
            order.id(), claim.paymentId(), claim.failureCode(), restored, adminId);
        return true;
    }

    /**
     * 수동 환불 확정 — 운영자가 PG · 결제 기록에서 환불을 확인한 환불 실패(실패 코드 무관)를 REFUNDED(재고 복원 대기, 다음 시각 {@code now + lease})로 바꾸고,
     * 주문이 취소 요청이면 취소 완료 + {@code ADMIN} 이력(사유)을 같은 커밋에 남긴다. 이어지는 재고 복원은 부르는 쪽이 트랜잭션 밖에서 잇는다.
     *
     * @return 확정했으면 저장된 클레임(올라간 버전), 이미 환불 확정 뒤(REFUNDED · COMPLETED · RESTORATION_FAILED)면 빈 값(아무것도 쓰지 않는다)
     * @throws OrderException 없는 클레임 {@code NOT_FOUND_CLAIM} · 그 밖의 상태 · 반품 {@code CLAIM_STATUS_INVALID} ·
     *     주문이 취소 요청 · 취소 완료가 아님 {@code ORDER_STATUS_TRANSITION_INVALID}
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳이 클레임 · 주문을 먼저 바꿨을 때
     */
    @Transactional(timeout = 5)
    public Optional<Claim> confirmRefund(long claimId, String reason, long adminId) {
        Claim claim = loadCancellation(claimId);
        if (isRefundedOrLater(claim.status())) {
            return Optional.empty();
        }
        if (claim.status() != Claim.Status.REFUND_FAILED) {
            throw invalid(claim);
        }
        Order order = loadOrder(claim.orderId());
        Claim refunded = claim.confirmRefundByAdmin(decision(reason, adminId), clock.instant().plus(properties.retry().lease()));
        Claim saved;
        if (order.status() == OrderStatus.CANCEL_REQUESTED) {
            saved = claimRepositoryPort.update(refunded);
            Order canceled = order.completeCancellation();
            orderRepositoryPort.saveStatusChange(canceled, OrderStatusHistory.changedByAdmin(snowflakeIdGenerator.generateId(), order, canceled, adminId, reason));
        } else if (order.status() == OrderStatus.CANCELED) {
            saved = claimRepositoryPort.update(refunded);
        } else {
            log.error("refund-failed cancellation claim but order is not cancel-requested. claimId={}, orderId={}, status={}", claim.id(), order.id(),
                order.status());
            throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName());
        }
        log.warn("cancellation refund confirmed by admin. claimId={}, orderId={}, paymentId={}, failureCode={}, adminId={}", claim.id(), order.id(),
            claim.paymentId(), claim.failureCode(), adminId);
        return Optional.of(saved);
    }

    /**
     * 복원 생략 종결 — 재고 복원이 종결 실패한 클레임을 복원 없이 COMPLETED 로 닫는다(결정 칸). 재고는 운영자가 재고 조정으로 먼저 맞춘다. 주문은 바꾸지 않는다.
     *
     * @return 닫았으면 true, 이미 완료면 false(아무것도 쓰지 않는다)
     * @throws OrderException 없는 클레임 {@code NOT_FOUND_CLAIM} · 그 밖의 상태 · 반품 {@code CLAIM_STATUS_INVALID}
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳이 먼저 바꿨을 때
     */
    @Transactional(timeout = 5)
    public boolean complete(long claimId, String reason, long adminId) {
        Claim claim = loadCancellation(claimId);
        if (claim.status() == Claim.Status.COMPLETED) {
            return false;
        }
        if (claim.status() != Claim.Status.RESTORATION_FAILED) {
            throw invalid(claim);
        }
        claimRepositoryPort.update(claim.completeByAdmin(decision(reason, adminId)));
        log.warn("cancellation claim completed by admin without restoration. claimId={}, orderId={}, failureCode={}, adminId={}", claim.id(), claim.orderId(),
            claim.failureCode(), adminId);
        return true;
    }

    /** 환불이 확정된 뒤의 상태인가 — 수동 환불 확정이 이미 이룬 결과다. */
    public static boolean isRefundedOrLater(Claim.Status status) {
        return status == Claim.Status.REFUNDED || status == Claim.Status.COMPLETED || status == Claim.Status.RESTORATION_FAILED;
    }

    /** 상태 이력에서 취소 요청으로 바뀐 마지막 줄의 이전 상태 — 결제 완료 · 제작 중 · 상품 준비중이 아니면 빈 값이다. */
    private Optional<OrderStatus> statusBeforeCancellation(long orderId) {
        List<OrderStatusHistoryQueryResult> histories = orderRepositoryPort.findHistories(orderId);
        for (int index = histories.size() - 1; index >= 0; index--) {
            OrderStatusHistoryQueryResult history = histories.get(index);
            if (history.toStatus() == OrderStatus.CANCEL_REQUESTED) {
                return Optional.ofNullable(history.fromStatus()).filter(PAID_BEFORE_SHIPMENT::contains);
            }
        }
        return Optional.empty();
    }

    private Claim.Decision decision(String reason, long adminId) {
        return new Claim.Decision(reason, adminId, clock.instant());
    }

    private Claim load(long claimId) {
        return claimRepositoryPort.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
    }

    /** 결제 후 취소 클레임만 수습한다 — 반품 승인 · 거절은 #17 PR-B 다. */
    private Claim loadCancellation(long claimId) {
        Claim claim = load(claimId);
        if (claim.type() != Claim.Type.CANCELLATION) {
            throw invalid(claim);
        }
        return claim;
    }

    private Order loadOrder(long orderId) {
        return orderRepositoryPort.findById(orderId).orElseThrow(() -> new IllegalStateException("claim order not found: orderId=" + orderId));
    }

    private static OrderException invalid(Claim claim) {
        return new OrderException(OrderErrorCode.CLAIM_STATUS_INVALID, claim.type().getDisplayName() + " · " + claim.status().getDisplayName());
    }

    /**
     * 재시도 준비 결과.
     *
     * @param changed 다시 열었거나 선점했는가 — false 면 이미 완료라 원격 단계를 돌리지 않는다
     * @param claim 저장된 클레임(올라간 버전) 또는 읽은 그대로
     */
    public record Prepared(boolean changed, Claim claim) {
    }
}
