package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimAdminRecoveryPolicy;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 클레임의 운영자 재시도 DB 단계 (#17 A2b, {@code order-flow-guide.md} §5). 원격 단계(환불 · 재고 복원)는 이 트랜잭션 밖에서
 * {@link OrderCancellationPipelineProcessor} 가 잇는다 — 고객 취소 · 재시도 스케줄러와 같은 경로다.
 *
 * <p>운영자가 본 화면의 버전(expectedVersion)이 지금 저장된 버전과 같을 때만 선점한다. 선점은 다음 시각을 {@code now + lease} 로 미뤄 먼저 커밋하고,
 * 저장의 {@code @Version} 비교에서 이긴 쪽만 원격을 다시 보낸다 — 스케줄러 · 고객 · 다른 운영자와 겹쳐도 같은 환불이 동시에 두 번 나가지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimAdminRecoveryProcessor {

    /** payment 가 같은 claimId 의 취소를 받는 기간 — 처음 PENDING 뒤 14일이 지나면 영구 {@code PAYMENT_018} 이다. order 설정의 재시도 상한도 이 값에 맞춘다. */
    static final Duration REFUND_KEY_VALIDITY = Duration.ofDays(14);

    private final ClaimRepositoryPort claimRepositoryPort;
    private final OrderCancellationProperties properties;
    private final Clock clock;

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
        Claim claim = claimRepositoryPort.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
        if (!Objects.equals(claim.version(), expectedVersion)) {
            throw new OrderException(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
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
        if (claim.type() != Claim.Type.CANCELLATION) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        }
        if (claim.status().isAwaitingAttempt() && claim.nextAttemptAt() != null && claim.nextAttemptAt().isAfter(now)) {
            throw new OrderException(OrderErrorCode.CLAIM_AUTO_PROCESSING);
        }
        // 결제사가 거절한 환불은 다시 보내도 같은 거절(PAYMENT_016)이고 진짜 사유만 지워진다 — 대표 관리자의 수동 확정으로 넘긴다
        if (claim.isRefundRejected()) {
            throw new OrderException(OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        }
        // 결제 취소 키가 끝난 뒤에는 payment 가 018 로 막는다 — 자동 처리 중으로 묻히지 않게 다시 시도부터 받지 않는다
        if (isRefundStage(claim) && !now.plus(properties.retry().lease()).isBefore(refundDeadline(claim))) {
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

    private static boolean isRefundStage(Claim claim) {
        return claim.status() == Claim.Status.REQUESTED || claim.status() == Claim.Status.REFUND_FAILED;
    }

    /** 접수 시각 + 결제 취소 키 유효 기간({@code payment-guide.md} — PENDING 14일 뒤 {@code PAYMENT_018}). */
    private static Instant refundDeadline(Claim claim) {
        return claim.requestedAt().plus(REFUND_KEY_VALIDITY);
    }
}
