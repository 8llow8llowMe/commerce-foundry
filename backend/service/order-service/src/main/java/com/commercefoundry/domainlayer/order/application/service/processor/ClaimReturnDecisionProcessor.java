package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.ClaimReturnApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.Blocker;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.FaultRefund;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.ReturnDecision;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.time.Clock;
import java.util.ArrayList;
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
 * 반품 결정 (#17 B2c) — 결정 대기 반품({@link Claim#isAwaitingReturnDecision()})의 미리보기(B2c-1)와 운영자 승인 · 거절(B2c-2).
 *
 * <p><b>판정은 미리보기와 승인이 같은 메서드를 쓴다</b>({@link #decidable} → {@link #assessApproval} → {@link #calculate}) — 둘이 갈라지지 않는다.
 * 순서: 반품 접수가 아님(읽는 사이 승인 · 거절된 경우 포함) · 주문 없음 · 주문이 반품 요청이 아님 {@link Blocker#NOT_DECIDABLE}(409 {@code ORDER_042}),
 * 같은 주문의 다른 반품 환불이 진행 중({@link Claim#isReturnRefundInFlight()}) {@link Blocker#RETURN_IN_FLIGHT}(409 {@code ORDER_043}), 결제 기록 없음 ·
 * 결제 금액과 주문 총액 불일치 · 산식 예외 {@link Blocker#NOT_CALCULABLE}(409 {@code ORDER_045}). 거절은 {@link Blocker#NOT_DECIDABLE} 만 막는다
 * ({@link ReturnDecision#rejectable()}). 결제 대조는 결제 후 고객 취소({@link OrderCancellationProcessor#request})와 같다 — {@code order_payment} 의 결제 완료 기록
 * 금액이 주문 총액과 같아야 한다. 주문 없음 · 계산 불가는 데이터 이상이라 error 로그를 남긴다(금액은 남기지 않는다). 주문 상태 이상은 잠금 아래(승인 · 거절)에서만
 * error, 미리보기는 warn 이다. 데이터 판정이 모두 통과해도 재시도 스케줄러가 꺼져 있으면 미리보기는 {@link Blocker#REFUND_NOT_OPEN}(승인 503 {@code ORDER_040})이다.
 *
 * <p>미리보기는 잠그지 않는다. 승인 · 거절은 반품의 열림 → 결정 전이라 <b>주문을 먼저 NOWAIT 으로 잠근 뒤</b>({@code order-flow-guide.md} §1-2) 클레임 목록을 읽어
 * 같은 판정 · 산식을 다시 한다(정본은 그쪽이다). 잠금에서 지면 기다리지 않고 409 {@code ORDER_037} 이다. 같은 클레임의 동시 결정도 저장의 버전 대조에서 037 로
 * 막힌다({@link #decidable} 참고). payment-service 는 부르지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimReturnDecisionProcessor {

    private final OrderRepositoryPort orderRepositoryPort;
    private final ClaimRepositoryPort claimRepositoryPort;
    private final OrderPaymentRepositoryPort orderPaymentRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final OrderCancellationProperties properties;
    private final Clock clock;

    /**
     * 반품 결정 미리보기. 결정 대기 반품이 아니면(취소 클레임 포함) 주문을 읽지 않고 {@link Blocker#NOT_DECIDABLE} 이다 — 취소 클레임은 기본 귀책도 없다(null).
     * 주문이 없거나 반품 요청이 아니면 클레임 목록 · 결제를 읽지 않는다.
     *
     * <p>트랜잭션은 부르는 쪽(어드민 상세 {@link ClaimAdminProcessor}, 읽기 전용 · 5초)에 합류한다 — 여기서는 읽기 전용만 표시하고 시간 제한을 따로 두지 않는다.
     *
     * @param claim 지금 저장된 클레임
     */
    @Transactional(readOnly = true)
    public ReturnDecision preview(Claim claim) {
        if (claim.type() != Claim.Type.RETURN) {
            return ReturnDecision.blocked(Blocker.NOT_DECIDABLE, null);
        }
        ReturnRefundPolicy.Fault defaultFault = ReturnRefundPolicy.Fault.defaultOf(claim);
        if (!claim.isAwaitingReturnDecision()) {
            return ReturnDecision.blocked(Blocker.NOT_DECIDABLE, defaultFault);
        }
        Decidable decidable = decidable(claim, orderRepositoryPort.findById(claim.orderId()), false);
        if (decidable == null) {
            return ReturnDecision.blocked(Blocker.NOT_DECIDABLE, defaultFault);
        }
        Assessment assessment = assessApproval(claim, decidable);
        if (assessment.blocker() != null) {
            return ReturnDecision.blocked(assessment.blocker(), defaultFault);
        }
        List<FaultRefund> refunds = new ArrayList<>();
        for (ReturnRefundPolicy.Fault fault : ReturnRefundPolicy.Fault.values()) {
            ReturnRefundPolicy.Refund refund = calculate(claim, assessment, fault);
            if (refund == null) {
                return ReturnDecision.blocked(Blocker.NOT_CALCULABLE, defaultFault);
            }
            refunds.add(new FaultRefund(fault, refund.policyAmount(), refund.currency()));
        }
        // 승인의 503 ORDER_040 게이트({@link #approve})와 같다 — 데이터 판정이 모두 통과했을 때만 내서 운영자가 데이터 이유를 먼저 본다. 금액은 표시용으로 싣는다
        if (!properties.paidCancellationOpen()) {
            return ReturnDecision.refundNotOpen(defaultFault, refunds);
        }
        return ReturnDecision.approvable(defaultFault, refunds);
    }

    /**
     * 반품 승인 (#17 B2c-2) — 결정 대기 반품을 환불 대기(APPROVED)로 바꾼다. 환불(PG)은 커밋 뒤 부르는 쪽이 트랜잭션 밖에서 잇는다 — 승인이 남긴 lease 동안은
     * 이 요청이, 그 뒤는 재시도 스케줄러가 환불한다. 그래서 재시도 스케줄러가 꺼져 있으면 받지 않는다(503 {@code ORDER_040}, 다시 시도 · 수동 확정과 같은 게이트).
     *
     * <p>귀책이 없으면 기본 귀책이다. 서버가 계산한 환불액(정책 금액 − 차감액)이 0 이하면 409 {@code ORDER_044}, 운영자가 본 환불액 · 통화와 다르면(차감액 통화가
     * 다른 경우 포함) 409 {@code ORDER_046} 이다. 주문 상태는 바꾸지 않는다(반품 요청에 머문다).
     *
     * @return 저장된 승인 클레임(올라간 버전) — 이 값으로 환불을 잇는다
     * @throws OrderException 게이트 {@code CLAIM_RETRY_UNAVAILABLE} · 없음 {@code NOT_FOUND_CLAIM} · 버전 불일치 · 주문 잠금 경합 · 저장 경합
     *     {@code CLAIM_CONCURRENTLY_CHANGED} · 판정 {@code RETURN_NOT_DECIDABLE} · {@code RETURN_REFUND_IN_FLIGHT} · {@code RETURN_REFUND_NOT_CALCULABLE} ·
     *     {@code RETURN_REFUND_NOT_PAYABLE} · {@code RETURN_REFUND_CHANGED}
     */
    @Transactional(timeout = 5)
    public Claim approve(ClaimReturnApprovalCommand command, long adminId) {
        if (!properties.paidCancellationOpen()) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_UNAVAILABLE);
        }
        Claim claim = loadAwaitingDecision(command.claimId(), command.expectedVersion());
        Decidable decidable = requireDecidable(claim, lockOrder(claim));
        Assessment assessment = assessApproval(claim, decidable);
        if (assessment.blocker() != null) {
            throw new OrderException(errorCodeOf(assessment.blocker()));
        }
        ReturnRefundPolicy.Fault fault = command.fault() != null ? command.fault() : ReturnRefundPolicy.Fault.defaultOf(claim);
        ReturnRefundPolicy.Refund policy = calculate(claim, assessment, fault);
        if (policy == null) {
            throw new OrderException(OrderErrorCode.RETURN_REFUND_NOT_CALCULABLE);
        }
        ReturnRefundPolicy.Refund refund;
        try {
            refund = policy.withDeduction(command.deduction());
        } catch (IllegalArgumentException currencyMismatch) {
            // 차감액 통화가 주문 통화와 다르다 — 화면이 본 금액 단위가 다르다(음수 차감액은 요청 검증 · Money 가 먼저 막아 여기 오지 않는다)
            throw new OrderException(OrderErrorCode.RETURN_REFUND_CHANGED);
        }
        if (!refund.isPayable()) {
            throw new OrderException(OrderErrorCode.RETURN_REFUND_NOT_PAYABLE);
        }
        if (!refund.toMoney().equals(command.expectedRefund())) {
            throw new OrderException(OrderErrorCode.RETURN_REFUND_CHANGED);
        }
        Order order = decidable.order();
        Claim approved;
        try {
            approved = claim.approveReturn(order, assessment.payment().paymentId(), refund, command.restock(), adminId, clock.instant(),
                properties.retry().lease());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            // 방어선 — 지금은 도달하지 않는다. 반품 · REQUESTED 는 loadAwaitingDecision, 같은 주문 · 저장된 주문은 claim.orderId() 로 잠근 주문, 주문에 없는 항목은
            // ReturnRefundPolicy#refund(→ 045), 0 이하는 isPayable(→ 044), 통화는 주문 통화로 계산, 정책 금액 ≤ 결제 총액은 산식(상품 합계 + 배송비 = 총액, 할인 없음),
            // lease 는 설정 검증(30초~1시간)이 이미 막는다. 할인 · 부분 취소(#684)가 산식에 들어오면 다시 본다

            log.error("return approval refused, claim does not match order. claimId={}, orderId={}, reason={}", claim.id(), order.id(),
                invalid.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.RETURN_REFUND_NOT_CALCULABLE);
        }
        Claim saved;
        try {
            saved = claimRepositoryPort.update(approved);
        } catch (OptimisticLockingFailureException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        log.warn("return approved by admin. claimId={}, orderId={}, paymentId={}, fault={}, restock={}, adminId={}", claim.id(), order.id(),
            saved.paymentId(), fault, command.restock(), adminId);
        return saved;
    }

    /**
     * 반품 거절 (#17 B2c-2) — 결정 대기 반품을 거절(REJECTED, 끝)로 바꾸고 같은 커밋에서 주문을 정리한다({@link ReturnRefundPolicy#outcome} →
     * {@link Order#settleReturn}, 바뀌었으면 {@code ADMIN} 이력 · 사유). 열린 반품이 남으면 주문은 반품 요청에 머물고, 정산한 반품이 없으면 배송 완료로 돌아간다.
     * 진행 중 반품 · 결제 대조는 보지 않는다 — 환불이 없다. 게이트도 없다(원격 단계가 없다).
     *
     * @return 저장된 거절 클레임(올라간 버전)
     * @throws OrderException 없음 {@code NOT_FOUND_CLAIM} · 버전 불일치 · 주문 잠금 경합 · 저장 경합 {@code CLAIM_CONCURRENTLY_CHANGED} ·
     *     결정 대상 아님 · 주문 정리 판정 불가 {@code RETURN_NOT_DECIDABLE}
     */
    @Transactional(timeout = 5)
    public Claim reject(long claimId, long expectedVersion, String reason, long adminId) {
        Claim claim = loadAwaitingDecision(claimId, expectedVersion);
        Decidable decidable = requireDecidable(claim, lockOrder(claim));
        Order order = decidable.order();
        Claim rejected = claim.rejectReturn(new Claim.Decision(reason, adminId, clock.instant(), null));
        List<Claim> claims = decidable.claims().stream().map(stored -> stored.id() == rejected.id() ? rejected : stored).toList();
        ReturnRefundPolicy.ReturnOutcome outcome;
        try {
            outcome = ReturnRefundPolicy.outcome(order, claims);
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            log.error("return rejection refused, order claims cannot be settled. claimId={}, orderId={}, reason={}", claim.id(), order.id(),
                invalid.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.RETURN_NOT_DECIDABLE);
        }
        Claim saved;
        Order settled;
        try {
            saved = claimRepositoryPort.update(rejected);
            settled = order.settleReturn(outcome);
            if (settled.status() != order.status()) {
                orderRepositoryPort.saveStatusChange(settled,
                    OrderStatusHistory.changedByAdmin(snowflakeIdGenerator.generateId(), order, settled, adminId, reason));
            }
        } catch (OptimisticLockingFailureException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        log.warn("return rejected by admin. claimId={}, orderId={}, orderStatus={}, adminId={}", claim.id(), order.id(), settled.status(), adminId);
        return saved;
    }

    private Claim loadAwaitingDecision(long claimId, long expectedVersion) {
        Claim claim = claimRepositoryPort.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
        if (!Objects.equals(claim.version(), expectedVersion)) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        // 클레임만으로 결정 대상이 아니면 주문을 잠그지 않고 바로 거절한다 — 잠금은 실제로 바꿀 요청만 잡는다
        if (!claim.isAwaitingReturnDecision()) {
            throw new OrderException(OrderErrorCode.RETURN_NOT_DECIDABLE);
        }
        return claim;
    }

    private Optional<Order> lockOrder(Claim claim) {
        try {
            return orderRepositoryPort.findByIdForUpdate(claim.orderId());
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException exception) {
            // 같은 주문의 다른 반품 결정 · 환불 기록 · 고객 접수가 잡고 있다 — 기다리지 않고 화면을 다시 읽게 한다
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
    }

    private Decidable requireDecidable(Claim claim, Optional<Order> found) {
        Decidable decidable = decidable(claim, found, true);
        if (decidable == null) {
            throw new OrderException(OrderErrorCode.RETURN_NOT_DECIDABLE);
        }
        return decidable;
    }

    /**
     * 결정 대상 판정(미리보기 · 승인 · 거절 공통) — 주문 없음 · 주문이 반품 요청이 아님 · 읽은 클레임 목록에서 대상이 결정 대기가 아님(그 사이 결정됨 — 경쟁이라 로그
     * 없음)이면 null({@link Blocker#NOT_DECIDABLE})이다. 주문이 반품 요청일 때만 클레임 목록을 읽는다.
     *
     * <p>목록의 대상 재확인은 <b>미리보기에서만 실제로 걸린다</b>. 승인 · 거절은 버전 대조로 대상 클레임을 먼저 읽어 영속성 컨텍스트에 올리므로, 주문 잠금 뒤
     * {@code findAllByOrderId} 도 그 같은 (먼저 읽은) 상태를 돌려준다. 그래서 다른 결정이 그 사이 커밋했어도 여기서 042 가 아니라 저장의 {@code @Version} 대조에서
     * 409 {@code ORDER_037}(동시 변경)로 막힌다 — 결과(두 번째 결정이 저장되지 않음)는 같다.
     *
     * @param locked 주문을 잠근 결정 경로인가. 주문이 반품 요청이 아닌 것은 잠금 아래에서는 데이터 이상(error), 잠그지 않은 미리보기에서는 경쟁으로도 날 수 있어 warn 이다
     */
    private Decidable decidable(Claim claim, Optional<Order> found, boolean locked) {
        if (found.isEmpty()) {
            log.error("return claim has no order. claimId={}, orderId={}", claim.id(), claim.orderId());
            return null;
        }
        Order order = found.get();
        if (order.status() != OrderStatus.RETURN_REQUESTED) {
            // 결정 대기 반품이 있는데 주문이 반품 요청이 아니다 — 잠금 아래라면 반품 정리 경로가 지켜졌을 때 생기지 않는다.
            // 미리보기는 클레임 · 주문을 잠그지 않고 따로 읽어 그 사이 거절 · 정리가 끼면 경쟁으로도 보인다
            if (locked) {
                log.error("return claim awaiting decision but order is not return requested. claimId={}, orderId={}, orderStatus={}", claim.id(),
                    order.id(), order.status());
            } else {
                log.warn("return claim awaiting decision but order is not return requested. claimId={}, orderId={}, orderStatus={}", claim.id(),
                    order.id(), order.status());
            }
            return null;
        }
        List<Claim> claims = claimRepositoryPort.findAllByOrderId(order.id());
        if (claims.stream().anyMatch(current -> current.id() == claim.id() && !current.isAwaitingReturnDecision())) {
            return null;
        }
        return new Decidable(order, claims);
    }

    /**
     * 승인 판정 — 진행 중 다른 반품 {@link Blocker#RETURN_IN_FLIGHT}, 결제 기록 없음 · 결제 금액 ≠ 주문 총액 · 정산 모으기 실패 {@link Blocker#NOT_CALCULABLE}.
     * 진행 중 반품이면 결제를 읽지 않는다.
     */
    private Assessment assessApproval(Claim claim, Decidable decidable) {
        Order order = decidable.order();
        if (decidable.claims().stream().anyMatch(other -> other.id() != claim.id() && other.isReturnRefundInFlight())) {
            return Assessment.blocked(Blocker.RETURN_IN_FLIGHT);
        }
        Optional<OrderPayment> payment = orderPaymentRepositoryPort.findPaidByOrderId(order.id());
        if (payment.isEmpty()) {
            log.error("return order has no payment record. claimId={}, orderId={}", claim.id(), order.id());
            return Assessment.blocked(Blocker.NOT_CALCULABLE);
        }
        if (!payment.get().totalAmount().equals(order.quote().totalAmount())) {
            // 금액은 로그에 남기지 않는다 — 주문 · 결제 아이디로 운영자가 대조한다
            log.error("return order total differs from approved payment amount. claimId={}, orderId={}, paymentId={}", claim.id(), order.id(),
                payment.get().paymentId());
            return Assessment.blocked(Blocker.NOT_CALCULABLE);
        }
        try {
            return new Assessment(null, order, payment.get(), ReturnRefundPolicy.Settled.of(order, decidable.claims(), claim));
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
            logCalculationFailure(claim, order, failure);
            return Assessment.blocked(Blocker.NOT_CALCULABLE);
        }
    }

    /** 귀책 하나의 정책 금액(차감 전). 산식이 거부하면 null({@link Blocker#NOT_CALCULABLE}). */
    private ReturnRefundPolicy.Refund calculate(Claim claim, Assessment assessment, ReturnRefundPolicy.Fault fault) {
        try {
            return ReturnRefundPolicy.refund(assessment.order(), assessment.settled(), claim, fault);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
            logCalculationFailure(claim, assessment.order(), failure);
            return null;
        }
    }

    /** 지금 전이표로는 도달하지 않는 데이터 이상(거절이 아닌 취소 클레임 · 누적 수량 초과 · 통화 불일치 등) — 메시지에 금액이 있을 수 있어 예외 이름만 남긴다. */
    private static void logCalculationFailure(Claim claim, Order order, RuntimeException failure) {
        log.error("return refund calculation failed. claimId={}, orderId={}, reason={}", claim.id(), order.id(), failure.getClass().getSimpleName());
    }

    private static OrderErrorCode errorCodeOf(Blocker blocker) {
        return switch (blocker) {
            case NOT_DECIDABLE -> OrderErrorCode.RETURN_NOT_DECIDABLE;
            case RETURN_IN_FLIGHT -> OrderErrorCode.RETURN_REFUND_IN_FLIGHT;
            case NOT_CALCULABLE -> OrderErrorCode.RETURN_REFUND_NOT_CALCULABLE;
            // 승인은 판정 전에 게이트(503)를 먼저 본다 — assessApproval 은 이 값을 내지 않는다. 미리보기 전용이지만 짝은 같다
            case REFUND_NOT_OPEN -> OrderErrorCode.CLAIM_RETRY_UNAVAILABLE;
        };
    }

    /** 결정 대상 — 잠근(또는 읽은) 주문과 그 주문의 클레임 전부. */
    private record Decidable(Order order, List<Claim> claims) {
    }

    /** 승인 판정 결과 — 막는 이유가 있으면 나머지는 비어 있다. */
    private record Assessment(Blocker blocker, Order order, OrderPayment payment, ReturnRefundPolicy.Settled settled) {
        static Assessment blocked(Blocker blocker) {
            return new Assessment(blocker, null, null, null);
        }
    }
}
