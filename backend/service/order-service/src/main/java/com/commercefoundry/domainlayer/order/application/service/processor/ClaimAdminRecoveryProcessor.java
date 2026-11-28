package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimAdminRecoveryPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusTransitionException;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 클레임의 운영자 수습 DB 단계 (#17, {@code order-flow-guide.md} §5) — 다시 시도 선점(A2b) · 수동 환불 확정 · 복원 생략 종결(A3).
 * 원격 단계(환불 · 재고 복원)는 이 트랜잭션 밖에서 {@link OrderCancellationPipelineProcessor} 가 잇는다 — 고객 취소 · 재시도 스케줄러와 같은 경로다.
 *
 * <p>운영자가 본 화면의 버전(expectedVersion)이 지금 저장된 버전과 같을 때만 바꾼다. 저장의 {@code @Version} 비교에서 이긴 쪽만 커밋하므로
 * 스케줄러 · 고객 · 다른 운영자와 겹쳐도 같은 환불이 동시에 두 번 나가거나 결정이 두 번 남지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimAdminRecoveryProcessor {

    /** payment 가 같은 claimId 의 취소를 받는 기간 — 처음 PENDING 뒤 14일이 지나면 영구 {@code PAYMENT_018} 이다. order 설정의 재시도 상한도 이 값에 맞춘다. */
    static final Duration REFUND_KEY_VALIDITY = Duration.ofDays(14);

    private final ClaimRepositoryPort claimRepositoryPort;
    private final OrderRepositoryPort orderRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final OrderCancellationProperties properties;
    private final Clock clock;

    /**
     * 수동 환불 확정 (#17 A3, 반품 B2b, 부분 취소 #684 PR-2) — 대표 관리자가 PG · 결제 기록에서 환불을 확인한 환불 실패를 REFUNDED(재고 복원 대기)로 바꾸고,
     * 같은 커밋에서 주문을 정리한다 — 취소는 취소 정리({@link CancellationRefundPolicy#outcome(Order, List, Claim)} → {@link Order#settleCancellation} — 전량이면
     * 취소 완료, 일부면 결제 완료로 복귀), 반품은 반품 정리({@link ReturnRefundPolicy#outcome} → {@link Order#settleReturn}). 바뀌었으면 {@code ADMIN} 이력(사유)이다.
     * 자동 반영({@code OrderCancellationProcessor#applyCancellationRefunded} · {@code #applyReturnRefunded})과 같은 정리이고 이력만 SERVICE 대신 ADMIN 이다.
     * PG 를 부르지 않는다 — 이어지는 재고 복원은 부르는 쪽이 트랜잭션 밖에서 잇는다.
     *
     * <p>열림 → 정산 전이라 <b>주문을 먼저 NOWAIT 으로 잠근다</b>({@code order-flow-guide.md} §1-2) — 클레임만으로 후보가 아니면 잠그지 않고 거절한다.
     * 이어서 클레임을 NOWAIT 으로 잠가 다시 읽고 화면 버전을 다시 대조한다(주문 → 클레임 순서, 다르면 409). 판정 입력은 잠금 뒤 읽은 클레임 목록에 이번 클레임의 확정 결과를 바꿔 넣은 것이다. 정리 판정이 불가능하면(데이터 이상) 저장하지 않고 409 다 — 자동 반영과 달리
     * 운영자 앞이라 fail-closed 저장 대신 거절한다. 재고로 되돌리지 않는 반품은 같은 커밋에서 COMPLETED 다.
     *
     * <p>자격은 {@link ClaimAdminRecoveryPolicy#requireRefundConfirmation} 이 본다 — 결제사 거절은 늘, 결과 미확인 · 반품의 막힘은 다시 시도가 결제 취소 키
     * 기한으로 닫혔을 때만({@link #isRefundRetryClosed}) 받는다. 기한 안이면 다시 시도가 PG 결과를 확인한다.
     *
     * @return 저장된 클레임(올라간 버전)
     * @throws OrderException 재시도 스케줄러 꺼짐 {@code CLAIM_RETRY_UNAVAILABLE} · 없음 {@code NOT_FOUND_CLAIM} · 버전 불일치 · 주문 잠금 · 저장 경합
     *     {@code CLAIM_CONCURRENTLY_CHANGED} · 자격 없음(환불 실패가 아님 · 기한 안의 결과 미확인 · 주문 상태가 맞지 않음 · 정리 판정 불가)
     *     {@code CLAIM_DECISION_NOT_ALLOWED}
     */
    @Transactional(timeout = 5)
    public Claim confirmRefund(long claimId, long expectedVersion, String reason, long adminId) {
        // 확정 뒤 재고 복원이 미확정으로 끝나면 스케줄러가 이어야 한다 — 다시 시도와 같은 게이트다
        if (!properties.paidCancellationOpen()) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_UNAVAILABLE);
        }
        Claim candidate = loadExpected(claimId, expectedVersion);
        // 클레임만으로 후보가 아니면 주문을 잠그지 않고 바로 거절한다 — 잠금은 실제로 바꿀 요청만 잡는다
        if (!ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(candidate, isRefundRetryClosed(candidate, clock.instant()))) {
            log.warn("refund confirmation refused. claimId={}, orderId={}, claimStatus={}, failureCode={}", candidate.id(), candidate.orderId(),
                candidate.status(), candidate.failureCode());
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        Order order;
        Claim claim;
        try {
            // 주문 → 클레임 순서로 잠근다(§1-2). 잠근 클레임의 버전을 화면 버전과 다시 대조한다 — 첫 읽기와 잠금 사이에 다른 쪽이 바꿨으면 그 값으로 판정하지 않는다
            order = orderRepositoryPort.findByIdForUpdate(candidate.orderId()).orElseThrow(() -> new IllegalStateException("claim order not found"));
            claim = claimRepositoryPort.findByIdForUpdate(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException exception) {
            // 같은 주문의 다른 클레임 결정 · 환불 기록이 잡고 있다 — 기다리지 않고 화면을 다시 읽게 한다
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        if (!Objects.equals(claim.version(), expectedVersion)) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        Instant now = clock.instant();
        requireRefundConfirmation(claim, order, now);
        Claim refunded = claim.confirmRefundByAdmin(decision(claim, reason, adminId, now), now.plus(properties.retry().lease()));
        Claim recorded = refunded.requiresRestoration() ? refunded : refunded.complete();
        Order settled;
        try {
            settled = settle(order, claimRepositoryPort.findAllByOrderId(order.id()), recorded);
        } catch (IllegalStateException | IllegalArgumentException | OrderStatusTransitionException invalid) {
            log.warn("refund confirmation refused, order claims cannot be settled. claimId={}, orderId={}, type={}, error={}", claim.id(), order.id(),
                claim.type(), invalid.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        Claim saved;
        try {
            saved = claimRepositoryPort.update(recorded);
            if (settled.status() != order.status()) {
                orderRepositoryPort.saveStatusChange(settled,
                    OrderStatusHistory.changedByAdmin(snowflakeIdGenerator.generateId(), order, settled, adminId, reason));
            }
        } catch (OptimisticLockingFailureException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        log.warn("{} refund confirmed by admin without PG call. claimId={}, orderId={}, paymentId={}, previousFailureCode={}, adminId={}, orderStatus={}",
            claim.type() == Claim.Type.RETURN ? "return" : "cancellation", claim.id(), claim.orderId(), claim.paymentId(), claim.failureCode(), adminId,
            settled.status());
        return saved;
    }

    /**
     * 확정과 함께 할 주문 정리 — 판정 입력은 잠금 뒤 읽은 목록({@code stored})에 이번 클레임의 확정 결과({@code recorded})를 바꿔 넣은 것이다.
     *
     * @throws IllegalStateException · IllegalArgumentException 정책의 데이터 이상 · {@link OrderStatusTransitionException} 정리 전이 불가
     */
    private static Order settle(Order order, List<Claim> stored, Claim recorded) {
        if (recorded.type() == Claim.Type.RETURN) {
            List<Claim> claims = stored.stream().map(claim -> claim.id() == recorded.id() ? recorded : claim).toList();
            return order.settleReturn(ReturnRefundPolicy.outcome(order, claims));
        }
        return order.settleCancellation(CancellationRefundPolicy.outcome(order, stored, recorded));
    }

    private void requireRefundConfirmation(Claim claim, Order order, Instant now) {
        try {
            ClaimAdminRecoveryPolicy.requireRefundConfirmation(claim, order, isRefundRetryClosed(claim, now));
        } catch (IllegalStateException exception) {
            log.warn("refund confirmation refused. claimId={}, orderId={}, claimStatus={}, failureCode={}, orderStatus={}", claim.id(), order.id(),
                claim.status(), claim.failureCode(), order.status());
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
    }

    /**
     * 복원 생략 종결 (#17 A3) — 재고 복원이 종결 실패한(확정 실패 코드) 클레임을 복원 없이 COMPLETED 로 닫는다. 재고는 운영자가 재고 조정으로 먼저 맞춘다.
     * 원격을 부르지 않고 주문은 보기만 하고 바꾸지 않는다 — 주문 전 수량 취소는 주문이 취소 완료여야 하고, 일부 수량 취소(#684 — 정리 뒤 결제 완료로 돌아가
     * 출고 · 다음 취소로 넘어갔을 수 있다)와 반품은 주문 상태를 보지 않는다({@link ClaimAdminRecoveryPolicy#requireCompletion}). 정산 → 정산 전이라 주문을 잠그지 않는다.
     * 결과 미확인({@code RESTORATION_UNCONFIRMED})은 실제로 복원됐을 수 있어 종결하지 않는다(다시 시도).
     *
     * @return 저장된 클레임(올라간 버전)
     * @throws OrderException 없음 {@code NOT_FOUND_CLAIM} · 버전 불일치 · 저장 경합 {@code CLAIM_CONCURRENTLY_CHANGED} · 자격 없음 {@code CLAIM_DECISION_NOT_ALLOWED}
     */
    @Transactional(timeout = 5)
    public Claim complete(long claimId, long expectedVersion, String reason, long adminId) {
        Claim claim = loadExpected(claimId, expectedVersion);
        Order order = orderRepositoryPort.findById(claim.orderId()).orElseThrow(() -> new IllegalStateException("claim order not found"));
        try {
            ClaimAdminRecoveryPolicy.requireCompletion(claim, order);
        } catch (IllegalStateException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        Claim saved;
        try {
            saved = claimRepositoryPort.update(claim.completeByAdmin(decision(claim, reason, adminId, clock.instant())));
        } catch (OptimisticLockingFailureException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        log.warn("cancellation claim completed by admin without restoration. claimId={}, orderId={}, previousFailureCode={}, adminId={}", claim.id(),
            claim.orderId(), claim.failureCode(), adminId);
        return saved;
    }

    /** 어드민 상세의 "환불 완료로 확정" 표시 — {@link #confirmRefund} 의 게이트 · 클레임 조건과 같다. 주문 상태는 실행 때 본다. */
    public boolean isRefundConfirmable(Claim claim) {
        return properties.paidCancellationOpen() && ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(claim, isRefundRetryClosed(claim, clock.instant()));
    }

    /** 어드민 상세의 "재고 복원 없이 종결" 표시 — {@link #complete} 의 클레임 조건과 같다. 주문 상태는 실행 때 본다. */
    public boolean isCompletable(Claim claim) {
        return ClaimAdminRecoveryPolicy.isCompletionCandidate(claim);
    }

    private Claim loadExpected(long claimId, long expectedVersion) {
        Claim claim = claimRepositoryPort.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
        if (!Objects.equals(claim.version(), expectedVersion)) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        return claim;
    }

    private static Claim.Decision decision(Claim claim, String reason, long adminId, Instant now) {
        return new Claim.Decision(reason, adminId, now, claim.failureCode());
    }

    /**
     * 재시도 선점 — 종결 실패면 그 단계의 대기 상태로 다시 열고({@link Claim#reopen}), lease 가 끝난 대기 상태면 선점한다({@link Claim#holdUntil}).
     *
     * @param expectedVersion 운영자가 상세 화면에서 본 버전
     * @return 선점한 클레임(올라간 버전) — 이 값으로 원격 호출 · 결과 반영을 이어 간다
     * @throws OrderException 재시도 스케줄러 꺼짐 {@code CLAIM_RETRY_UNAVAILABLE} · 없음 {@code NOT_FOUND_CLAIM} · 버전 불일치 · 저장 경합
     *     {@code CLAIM_CONCURRENTLY_CHANGED} · 자동 처리 lease 안 {@code CLAIM_AUTO_PROCESSING} · 반품 · 처리 완료 · 주문 상태 이상
     *     {@code CLAIM_RETRY_NOT_ALLOWED}
     */
    @Transactional(timeout = 5)
    public Claim prepareRetry(long claimId, long expectedVersion) {
        // 스케줄러가 꺼진 채 다시 열면 이번 시도가 미확정으로 끝났을 때 따라갈 쪽이 없다 — 고객 접수(ORDER_035)와 같은 게이트다
        if (!properties.paidCancellationOpen()) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_UNAVAILABLE);
        }
        Claim claim = loadExpected(claimId, expectedVersion);
        Instant now = clock.instant();
        requireRetryable(claim, now);
        Instant until = now.plus(properties.retry().lease());
        Claim prepared = claim.status().isFailed() ? claim.reopen(until, startAttempt(claim, now)) : claim.holdUntil(until);
        Claim saved;
        try {
            saved = claimRepositoryPort.update(prepared);
        } catch (OptimisticLockingFailureException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        log.warn("cancellation claim retried by admin. claimId={}, orderId={}, from={}, to={}, failureCode={}", claim.id(), claim.orderId(), claim.status(),
            saved.status(), claim.failureCode());
        return saved;
    }

    /** 어드민 상세의 "다시 시도" 가능 여부 — {@link #prepareRetry} 가 버전 대조 뒤 받는 조건과 같다. */
    public boolean isRetryable(Claim claim) {
        if (!properties.paidCancellationOpen()) {
            return false;
        }
        try {
            requireRetryable(claim, clock.instant());
            return true;
        } catch (OrderException exception) {
            return false;
        }
    }

    private void requireRetryable(Claim claim, Instant now) {
        if (claim.isAwaitingAttempt() && claim.nextAttemptAt() != null && claim.nextAttemptAt().isAfter(now)) {
            throw new OrderException(OrderErrorCode.CLAIM_AUTO_PROCESSING);
        }
        // 결제사가 거절한 환불은 다시 보내도 같은 거절(PAYMENT_016)이고 진짜 사유만 지워진다 — 대표 관리자의 수동 확정으로 넘긴다.
        // 반품의 016 은 다른 환불에 막힌 것일 수 있어 거절이 아니다({@link Claim#isRefundWaiting}) — 같은 claimId 로 다시 보내는 것이 정본 경로다(#17 B2b)
        if (claim.isRefundRejected()) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        }
        // 결제 취소 키가 끝난 뒤에는 payment 가 018 로 막는다 — 자동 처리 중으로 묻히지 않게 다시 시도부터 받지 않는다(그 뒤는 수동 확정 몫)
        if (isRefundRetryClosed(claim, now)) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        }
        try {
            ClaimAdminRecoveryPolicy.requireRetry(claim, now);
        } catch (IllegalStateException exception) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        }
    }

    /**
     * 다시 연 단계의 시작 횟수. 환불 단계는 남은 자동 재시도({@code maxAttempts − n}회, 회당 최대 {@code maxBackoff})가 결제 취소 키 기한 안에 끝나도록
     * {@code n = maxAttempts − ⌊남은 기간 / maxBackoff⌋} 로 둔다 — order 설정의 {@code maxBackoff × maxAttempts ≤ 14일} 불변식을 다시 연 뒤에도 지킨다.
     * 재고 복원 단계는 product 에 기한이 없어 0 이다.
     */
    private int startAttempt(Claim claim, Instant now) {
        if (!isRefundStage(claim)) {
            return 0;
        }
        OrderCancellationProperties.Retry retry = properties.retry();
        long fits = Duration.between(now, refundDeadline(claim)).dividedBy(retry.maxBackoff());
        return (int) Math.max(0, Math.min(retry.maxAttempts(), retry.maxAttempts() - fits));
    }

    /**
     * 환불 단계의 다시 시도가 결제 취소 키 기한으로 닫혔는가 — {@code now + lease ≥ 기한 기준(취소는 접수) + 14일}. 다시 시도(038)와 결과 미확인 환불의 수동 확정 자격(#17 A3)이
     * 같은 기준을 쓴다 — 하나가 닫히는 순간 다른 하나가 열린다.
     */
    private boolean isRefundRetryClosed(Claim claim, Instant now) {
        return isRefundStage(claim) && !now.plus(properties.retry().lease()).isBefore(refundDeadline(claim));
    }

    /** 환불 대기(취소 REQUESTED · 반품 APPROVED) 또는 환불 실패 — 승인 전 반품(REQUESTED)은 아직 환불 단계가 아니다(#17 B1). */
    private static boolean isRefundStage(Claim claim) {
        return claim.isAwaitingRefund() || claim.status() == Claim.Status.REFUND_FAILED;
    }

    /**
     * 기한 기준(취소는 접수, 반품은 승인 시각 — {@link Claim#refundDeadlineBase()}) + 결제 취소 키 유효 기간
     * ({@code payment-guide.md} — PENDING 14일 뒤 {@code PAYMENT_018}).
     */
    private static Instant refundDeadline(Claim claim) {
        return claim.refundDeadlineBase().plus(REFUND_KEY_VALIDITY);
    }
}
