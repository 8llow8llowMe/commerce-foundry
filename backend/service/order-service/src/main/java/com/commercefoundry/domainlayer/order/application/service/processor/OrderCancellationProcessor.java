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
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusTransitionException;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * 결제 후 고객 취소의 DB 단계 (#16 PR-2, {@code order-flow-guide.md} §5) — 접수(tx1) · 환불 결과 반영(tx2) · 재고 복원 결과 반영(tx3) · 재시도 선점 · 실패 기록.
 * 승인된 반품(#17 B2a)도 tx2 · tx3 · 선점 · 실패 기록을 같이 쓴다 — 유형 분기는 환불 확정 반영의 정리 판정({@link #applyCancellationRefunded} ·
 * {@link #applyReturnRefunded})과 잠금 TX 의 잠금 순서(반품은 주문 먼저)뿐이다. 재조회는 두 유형 모두 클레임 아이디다(#684 PR-2 — 한 주문에 취소가 여럿일 수 있다).
 * 원격 호출(환불 · 복원)은 이 사이에 트랜잭션 밖에서 한다({@link OrderCancellationPipelineProcessor}). 각 단계는 짧은 트랜잭션(5초)이고 클레임 · 주문의
 * {@code @Version} 으로 진 쪽을 가른다 — 넘겨받은 클레임의 버전이 낡았으면 저장에서 {@code OptimisticLockingFailureException} 이다(다른 워커가 이미 처리했다).
 *
 * <p>결제 후 부분 취소(#684)를 받을 수 있게 일반화했다 — 전체 취소는 "남은 수량 전부를 취소하는 클레임"이고, 환불 확정은 주문을 잠근 뒤 이 주문의 클레임으로
 * 취소 정리를 판정한다({@link CancellationRefundPolicy#outcome(Order, List, Claim)} → {@link Order#settleCancellation}). 선행 부분 취소가 없으면 값 · 키 · 동작이
 * 전과 같다.
 *
 * <p>종결 실패(REFUND_FAILED · RESTORATION_FAILED)는 운영자가 어드민 클레임 목록으로 확인한다 — 저장할 때마다 클레임 · 주문 · 결제 아이디 · 실패 코드를
 * error 로그로 남긴다(금액은 남기지 않는다). 수습 화면은 #17 이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancellationProcessor {

    /**
     * 환불은 확정됐는데 주문을 정리할 수 없다(데이터 이상) — 주문이 취소 요청 · 취소 완료가 아니거나 취소 정리 판정이 불가능하다(#684). 재고 복원을 하지 않고
     * 운영자에게 넘긴다.
     */
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
     * <p>전체 취소는 남은 수량 전부를 취소한다 (#684 PR-2) — 주문 잠금 뒤 이 주문의 클레임을 읽어 거절 밖 취소에 잡히지 않은 수량 전부와 환불액 {@code due(before)} 로
     * 접수한다({@link CancellationRefundPolicy#remaining}). 결제 대조는 "정산된 취소 환불 합 + 이번 환불 = 결제 금액"이다. 선행 부분 취소가 없으면 전 항목 · 전 수량 ·
     * 결제 총액 · 결정적 키로 전과 같다.
     *
     * <p>이미 취소 요청 · 취소 완료이고 이 주문의 전체 취소 클레임(결정적 키)이 있으면 아무것도 쓰지 않고 재생({@code created = false})이다 — 결제 후 취소가 닫혀 있어도
     * 재생은 된다. 부분 취소만 열려 있는 취소 요청이면 전체 취소를 받지 않는다({@code ORDER_STATUS_TRANSITION_INVALID}).
     *
     * @throws OrderException 없거나 남의 주문 {@code NOT_FOUND_ORDER} · 취소할 수 없는 상태 {@code ORDER_STATUS_TRANSITION_INVALID} ·
     *     결제 후 취소가 닫힘 {@code CANCELLATION_UNAVAILABLE} · 결제 반영 기록 없음 {@code CANCELLATION_PAYMENT_MISSING} ·
     *     환불액과 승인 금액 불일치 · 클레임 데이터 이상 {@code CANCELLATION_AMOUNT_MISMATCH}
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
        // 재시도 스케줄러가 꺼진 채 받으면 미확정 환불 · 복원이 영영 따라오지 않는다 — 접수 전에 닫는다
        if (!properties.paidCancellationOpen()) {
            throw new OrderException(OrderErrorCode.CANCELLATION_UNAVAILABLE);
        }
        OrderPayment payment = orderPaymentRepositoryPort.findPaidByOrderId(orderId).orElseThrow(() -> {
            log.error("paid order has no payment record for cancellation. orderId={}, status={}", orderId, order.status());
            return new OrderException(OrderErrorCode.CANCELLATION_PAYMENT_MISSING);
        });
        CancellationRefundPolicy.RemainingCancellation remaining;
        try {
            remaining = CancellationRefundPolicy.remaining(order, claimRepositoryPort.findAllByOrderId(orderId));
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            // 열린 취소 · 수량 초과 등 클레임 데이터 이상 — 접수하지 않고 운영자가 클레임 목록으로 대조한다
            log.error("cancellation cannot be calculated from order claims. orderId={}, paymentId={}, error={}", orderId, payment.paymentId(),
                invalid.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.CANCELLATION_AMOUNT_MISMATCH);
        }
        // 정산된 취소 환불 합 + 이번 환불 = 결제 금액(통화 포함) — 선행 취소가 없으면 "전체 취소액 = 결제 금액" 그대로다
        if (!remaining.settledRefund().plus(remaining.refund()).equals(payment.totalAmount())) {
            // 금액은 로그에 남기지 않는다 — 주문 · 결제 아이디로 운영자가 대조한다
            log.error("cancellation refund differs from approved payment amount. orderId={}, paymentId={}", orderId, payment.paymentId());
            throw new OrderException(OrderErrorCode.CANCELLATION_AMOUNT_MISMATCH);
        }
        Claim claim = Claim.requestCancellation(snowflakeIdGenerator.generateId(), order, Claim.cancellationRequestKey(orderId), remaining.quantities(),
            payment.paymentId(), remaining.refund(), reason, snowflakeIdGenerator::generateId, clock.instant(), properties.retry().lease());
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

    /**
     * 클레임 하나(지금 저장된 값 · 버전, 유형 무관) — 환불 결과 반영에서 진 쪽의 재조회 키다. 한 주문에 반품(#17 B2a) · 취소(#684 PR-2 — 부분 취소)가 여럿일 수
     * 있어 주문의 결정적 키가 아니라 클레임 아이디로 읽는다. payment 의 환불 멱등 키도 클레임 아이디다.
     */
    @Transactional(readOnly = true, timeout = 5)
    public Optional<Claim> findClaimById(long claimId) {
        return claimRepositoryPort.findById(claimId);
    }

    /**
     * 환불 결과 반영(tx2).
     *
     * <ul>
     *   <li>DONE — 주문을 먼저 NOWAIT 으로 잠그고(지면 잠금 예외 — 파이프라인이 503 {@code ORDER_036}) 클레임 REFUNDED(다음 시각 {@code now + lease}, 이어서
     *       재고를 복원한다) + 취소 정리({@link #applyCancellationRefunded} — 전량이면 취소 완료, 일부면 결제 완료) + 바뀌었으면 {@code SERVICE} 이력.
     *       정리할 수 없으면(데이터 이상) 복원하지 않고 RESTORATION_FAILED({@link #ORDER_STATUS_UNEXPECTED}).
     *       미확정으로 닫힌 클레임({@link Claim#REFUND_UNCONFIRMED})의 늦은 성공도 받는다. 반품은 반품 정리다({@link #applyReturnRefunded})</li>
     *   <li>FAILED — 클레임 REFUND_FAILED(원격 코드). 주문은 취소 요청(반품은 반품 요청) 그대로 운영자에게 넘긴다</li>
     *   <li>RETRY — 재시도 상한 안이면 다음 시각을 미루고, 넘었으면 REFUND_FAILED({@link Claim#REFUND_UNCONFIRMED})</li>
     * </ul>
     *
     * @param claim 환불을 보낸 클레임(환불 대기 — 취소 REQUESTED · 반품 APPROVED) — 그 뒤 다른 쪽이 바꿨으면 저장에서 낙관적 락 예외다
     * @return 저장된 클레임(올라간 버전)
     */
    @Transactional(timeout = 5)
    public Claim recordRefund(Claim claim, CancellationStepOutcome outcome) {
        if (outcome.kind() == CancellationStepOutcome.Kind.DONE) {
            return applyRefunded(claim);
        }
        if (!claim.isAwaitingRefund()) {
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
     * RESTORATION_FAILED({@link Claim#RESTORATION_UNCONFIRMED}). 주문은 보지도 바꾸지도 않는다 — 취소 정리는 환불이 기록되는 전이(tx2 · 수동 환불 확정)에서
     * 이미 끝났다. 여기서 다시 정리하면 부분 취소 정리 뒤 상품 준비중 · 배송으로 넘어간 주문에 전이 예외가 난다(#684).
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
     * 일반 시도 실패를 당시 스냅숏의 id · version · status가 현재 대기 단계와 일치할 때만 한 번 센다. 새 선점 · 단계 · 종결 결과는 건드리지 않는다.
     * 확정 환불 미기록은 이 메서드로 보내지 않는다. 비교 뒤 경합도 UPDATE의 버전에서 패배한다. 반품 · 취소 모두 주문에 여럿일 수 있어 클레임 아이디로 다시 읽는다.
     */
    @Transactional(timeout = 5)
    public void recordAttemptFailure(Claim attempted) {
        claimRepositoryPort.findById(attempted.id())
            .filter(claim -> claim.id() == attempted.id() && Objects.equals(claim.version(), attempted.version())
                && claim.status() == attempted.status() && claim.isAwaitingAttempt())
            .ifPresent(this::retryOrGiveUp);
    }

    /**
     * 확정 성공의 낙관적 반영이 소진된 뒤 독립 TX에서 최신 claim → order를 NOWAIT로 잠근다(반품은 order → claim). 원격 호출 없이 함께 커밋하며,
     * 이미 환불 이후 단계면 빈 값으로 현재 실행의 복원 재진입을 막는다. 커밋 실패도 호출자에게 전달된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Optional<Claim> recordConfirmedRefundWithLock(Claim source) {
        // 반품은 클레임보다 주문을 먼저 잠근다(§1-2 반품 잠금 규칙) — 잠근 주문을 그대로 반영에 넘겨 같은 행을 다시 잠그지 않는다
        Order lockedReturnOrder = source.type() == Claim.Type.RETURN ? lockOrder(source.orderId()) : null;
        Claim current = claimRepositoryPort.findByIdForUpdate(source.id()).orElseThrow(() -> new IllegalStateException("claim not found"));
        if (!source.hasSameRefundRequest(current)) {
            throw new IllegalStateException("refund request changed");
        }
        if (current.hasRecordedRefund()) {
            return Optional.empty();
        }
        if (lockedReturnOrder != null) {
            return Optional.of(applyReturnRefunded(current, lockedReturnOrder));
        }
        // 취소는 A2a 그대로 클레임 → 주문 순서다(둘 다 NOWAIT 라 교착 없이 진 쪽이 바로 잠금 예외다)
        return Optional.of(applyCancellationRefunded(current, lockOrder(current.orderId())));
    }

    /** 재시도 대상 — 환불 대기 · 복원 대기이고 다음 시각이 지난 취소 · 반품 클레임을 다음 시각 순으로 {@code batch-size} 건까지(두 유형이 배치를 나눠 쓴다). */
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

    /**
     * 클레임의 열림 · 정산을 바꾸기 전에 주문을 NOWAIT 으로 잠근다 (#17 B2a · #684 PR-2, {@code order-flow-guide.md} §1-2) — 한 주문의 클레임 열림 · 정산을
     * 바꾸는 트랜잭션끼리 주문 행에서 직렬화해 write skew 를 막는다. 잠금에서 지면 기다리지 않고 잠금 예외가 나간다. 한 트랜잭션에서 한 번만 부른다.
     */
    private Order lockOrder(long orderId) {
        return orderRepositoryPort.findByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalStateException("claim order not found: orderId=" + orderId));
    }

    /** 주문의 전체 취소 클레임(결정적 키) — 전체 취소 접수의 재생 판정에만 쓴다. 부분 취소 클레임은 고객 멱등 키라 여기 잡히지 않는다. */
    private Optional<Claim> findCancellationClaim(long orderId) {
        return claimRepositoryPort.findByOrderIdAndRequestKey(orderId, Claim.cancellationRequestKey(orderId))
            .filter(claim -> claim.type() == Claim.Type.CANCELLATION);
    }

    /** 환불 확정 반영 — 주문을 먼저 잠그고(두 유형 모두, §1-2) 유형별 정리로 넘긴다. */
    private Claim applyRefunded(Claim claim) {
        Order order = lockOrder(claim.orderId());
        return claim.type() == Claim.Type.RETURN ? applyReturnRefunded(claim, order) : applyCancellationRefunded(claim, order);
    }

    /**
     * 취소 환불 확정 반영 (#684 PR-2) — <b>호출자가 {@link #lockOrder} 로 잠근 주문을 넘긴다</b>. 클레임 REFUNDED → 판정 입력(잠금 뒤 읽은 이 주문의 클레임 목록에
     * 이번 클레임의 갱신 결과를 바꿔 넣은 것)으로 {@link CancellationRefundPolicy#outcome(Order, List, Claim)} → {@link Order#settleCancellation} → 바뀌었으면
     * {@code SERVICE} 이력({@link OrderStatusHistory#cancellationSettled} — 전량 {@code CANCELLATION_REFUNDED}, 일부 {@code PARTIAL_CANCELLATION_REFUNDED})까지
     * 한 커밋이다. 열린 취소가 남았으면(주문당 하나라 정상 흐름에는 없다) 주문은 취소 요청에 머물고 이력이 없다. 이미 정리된 상태(예: 취소 완료)면 클레임만 바뀐다.
     * 운영자 수동 환불 확정({@link ClaimAdminRecoveryProcessor#confirmRefund})도 같은 정리를 쓴다 — 그쪽은 이력이 ADMIN · 사유다.
     *
     * <p><b>fail-closed</b> — 주문이 취소 요청 · 취소 완료가 아니거나 정리 판정이 불가능하면(정책의 데이터 이상 · 정리 전이 예외) 트랜잭션을 되돌리지 않는다 —
     * 환불은 이미 나갔다. 환불 기록은 남기되 클레임을 RESTORATION_FAILED({@link #ORDER_STATUS_UNEXPECTED})로 닫고 재고 · 주문은 건드리지 않는다(반품 B2a 와 같다).
     * 운영자 로그는 저장이 이긴 뒤에만 남긴다 — 낡은 클레임으로 온 늦은 반영은 저장의 버전 대조에서 지고 로그 없이 멈춘다.
     */
    private Claim applyCancellationRefunded(Claim claim, Order order) {
        Claim refunded = claim.markConfirmedRefund(clock.instant().plus(properties.retry().lease()));
        String unsettled = null;
        Order settled = order;
        if (order.status() != OrderStatus.CANCEL_REQUESTED && order.status() != OrderStatus.CANCELED) {
            unsettled = "order status";
        } else {
            try {
                settled = order.settleCancellation(CancellationRefundPolicy.outcome(order, claimRepositoryPort.findAllByOrderId(order.id()), refunded));
            } catch (IllegalStateException | IllegalArgumentException | OrderStatusTransitionException invalid) {
                unsettled = invalid.getClass().getSimpleName();
            }
        }
        if (unsettled != null) {
            // 환불은 이미 나갔다 — 재고 · 주문은 건드리지 않고 클레임을 종결 실패로 닫아 운영자에게 넘긴다
            Claim failed = saveFailure(refunded.failRestoration(ORDER_STATUS_UNEXPECTED));
            log.error("cancellation refunded but order cannot be settled, restoration skipped. claimId={}, orderId={}, paymentId={}, status={}, cause={}",
                claim.id(), claim.orderId(), claim.paymentId(), order.status(), unsettled);
            return failed;
        }
        Claim saved = claimRepositoryPort.update(refunded);
        if (settled.status() != order.status()) {
            orderRepositoryPort.saveStatusChange(settled, OrderStatusHistory.cancellationSettled(snowflakeIdGenerator.generateId(), order, settled));
        }
        return saved;
    }

    /**
     * 반품 환불 확정 반영 (#17 B2a) — <b>호출자가 {@link #lockOrder} 로 주문을 먼저 NOWAIT 으로 잠가 넘긴다</b>(반품 상태를 바꾸는 트랜잭션의 공통 규칙, {@code order-flow-guide.md} §1-2). 승인 · 거절 ·
     * 고객 추가 접수와 겹쳐 "열린 반품이 없는데 반품 요청에 멈춘 주문"이나 "열린 반품이 있는데 배송 완료로 돌아간 주문"이 생기지 않게 한다. 잠금에서 지면 잠금 예외가
     * 그대로 나가 파이프라인이 503 {@code ORDER_036} 으로 바꾸고, 클레임은 승인 + lease 로 남아 스케줄러가 같은 클레임으로 다시 보낸다(payment 200, PG 없음).
     *
     * <p>클레임 REFUNDED(재고로 되돌리지 않는 반품은 바로 COMPLETED) → 이 주문의 클레임 목록에 이 행을 메모리로 바꿔 넣어 {@link ReturnRefundPolicy#outcome} →
     * {@link Order#settleReturn} → 바뀌었으면 {@code SERVICE} 이력({@link OrderStatusHistory#RETURN_REFUNDED})까지 한 커밋이다. 주문이 반품 요청이 아니거나
     * 정리 판정이 불가능하면(데이터 이상 — 잠금 규칙상 도달하지 않는다) 재고를 건드리지 않고 RESTORATION_FAILED({@link #ORDER_STATUS_UNEXPECTED})로 닫는다.
     */
    private Claim applyReturnRefunded(Claim claim, Order order) {
        Claim refunded = claim.markConfirmedRefund(clock.instant().plus(properties.retry().lease()));
        Claim recorded = refunded.requiresRestoration() ? refunded : refunded.complete();
        ReturnRefundPolicy.ReturnOutcome outcome = null;
        if (order.status() == OrderStatus.RETURN_REQUESTED) {
            List<Claim> claims = claimRepositoryPort.findAllByOrderId(order.id()).stream()
                .map(stored -> stored.id() == recorded.id() ? recorded : stored).toList();
            try {
                outcome = ReturnRefundPolicy.outcome(order, claims);
            } catch (IllegalStateException | IllegalArgumentException invalid) {
                log.error("return refunded but order claims cannot be settled. claimId={}, orderId={}, error={}", claim.id(), claim.orderId(),
                    invalid.getClass().getSimpleName());
            }
        }
        if (outcome == null) {
            // 환불은 이미 나갔다 — 재고 · 주문은 건드리지 않고 클레임을 종결 실패로 닫아 운영자에게 넘긴다
            log.error("return refunded but order status unexpected, restoration skipped. claimId={}, orderId={}, paymentId={}, status={}",
                claim.id(), claim.orderId(), claim.paymentId(), order.status());
            return saveFailure(refunded.failRestoration(ORDER_STATUS_UNEXPECTED));
        }
        Claim saved = claimRepositoryPort.update(recorded);
        Order settled = order.settleReturn(outcome);
        if (settled.status() != order.status()) {
            orderRepositoryPort.saveStatusChange(settled,
                OrderStatusHistory.changedByService(snowflakeIdGenerator.generateId(), order, settled, OrderStatusHistory.RETURN_REFUNDED));
        }
        return saved;
    }

    /** 미확정 — 상한 안이면 다음 시각을 미루고, 넘었으면 그 단계의 미확정 코드(환불 {@code REFUND_UNCONFIRMED} · 복원 {@code RESTORATION_UNCONFIRMED})로 종결한다. */
    private Claim retryOrGiveUp(Claim claim) {
        OrderCancellationProperties.Retry retry = properties.retry();
        if (claim.attemptCount() >= retry.maxAttempts()) {
            return saveFailure(claim.isAwaitingRefund()
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
