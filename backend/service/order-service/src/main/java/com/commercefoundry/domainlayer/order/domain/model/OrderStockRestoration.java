package com.commercefoundry.domainlayer.order.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 결제 반영 재고 복원 작업 (#582, {@code orders.order_stock_restoration}) — 확정 선점 하나를 되돌릴 작업 한 건이다.
 * 결제 반영과 재고 복원 스윕이 사용하는 선점 · 클레임마다 한 행이다.
 *
 * <p>상태 전이는 아래 메서드로만 한다 — 바뀐 값을 돌려주고, 저장은 이전 값을 조건으로 건 조건부 UPDATE 다(두 처리가 같은 행을 덮어쓰지 않게).
 * <pre>
 *   RESTORE_PENDING ──claimAttempt──▶ RESTORE_PENDING (시도 +1, 다음 시각 = now + backoff)
 *   RESTORE_PENDING ──hold──▶ HELD (다시 집지 않는다 — 운영 SQL 로 RESTORE_PENDING 으로 되돌린다)
 *   RESTORE_PENDING · HELD ──복원 200──▶ RESTORED
 * </pre>
 * 마지막 전이는 컨슈머 인라인 마무리도 하므로(행을 읽지 않는다) 클레임 아이디 조건부 UPDATE 한 문장이다
 * ({@code OrderStockRestorationRepositoryPort#markRestored}) — 같은 클레임의 200 은 복원 증거라 HELD 에서도 끝낸다.
 *
 * @param claimId 재고 복원 클레임 아이디(product stock-restorations 멱등 키). 결제 기록 출처면 {@code order_payment.restoration_claim_id}
 * @param holdCode HELD 사유 — 상품 오류 코드(예: {@code PRODUCT_028}) 또는 {@link #OWNERSHIP_MISMATCH}
 * @param attempts 스윕이 선점한 횟수
 * @param nextAttemptAt 스윕이 다음에 집을 시각. 종결 상태면 null
 */
public record OrderStockRestoration(
    long id, long orderId, long paymentId, long stockReservationId, long claimId, Origin origin, Status status, String holdCode, int attempts,
    Instant nextAttemptAt, Instant restoredAt, Instant createdAt
) {

    /** 재검증(주문 · 회원 · 금액 · 결제 완료 기록) 불일치의 HELD 사유. */
    public static final String OWNERSHIP_MISMATCH = "OWNERSHIP_MISMATCH";

    public OrderStockRestoration {
        if (id <= 0 || orderId <= 0 || paymentId <= 0 || stockReservationId <= 0 || claimId <= 0 || attempts < 0) {
            throw new IllegalArgumentException("positive identifiers and non-negative attempts required");
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(status, "status");
        if (status.pending() != (nextAttemptAt != null) || (status == Status.RESTORED) != (restoredAt != null)
            || (status == Status.HELD ? holdCode == null || holdCode.isBlank() : holdCode != null)) {
            throw new IllegalArgumentException("restoration state mismatch: status=" + status);
        }
        nextAttemptAt = nextAttemptAt == null ? null : nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
        restoredAt = restoredAt == null ? null : restoredAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** 결제 기록(확정 뒤 취소 · 미승인 거절)이 정한 복원 의도 — 같은 트랜잭션에서 {@code order_payment} 와 함께 넣는다. */
    public static OrderStockRestoration forPaymentRecord(long id, OrderPayment record, Instant nextAttemptAt) {
        if (!record.awaitsRestoration()) {
            throw new IllegalArgumentException("restorable payment record required: paymentId=" + record.paymentId());
        }
        return new OrderStockRestoration(id, record.orderId(), record.paymentId(), record.stockReservationId(), record.restorationClaimId(),
            Origin.PAYMENT_RECORD, Status.RESTORE_PENDING, null, 0, nextAttemptAt, null, null);
    }

    /** 원격 confirm 전에 커밋하는 후보. 복원 대상으로 확정될 때부터 PAID를 막는다. */
    public static OrderStockRestoration forConfirmationAttempt(long id, long orderId, long paymentId, long reservationId, long claimId,
        Instant nextAttemptAt) {
        return new OrderStockRestoration(id, orderId, paymentId, reservationId, claimId, Origin.CONFIRMATION_ATTEMPT, Status.RESTORE_PENDING,
            null, 0, nextAttemptAt, null, null);
    }

    /** 확정 시도의 소유권을 주문 잠금에서 판단한 뒤 복원 대상으로 전환한다. */
    public OrderStockRestoration abandonConfirmation() {
        if (origin != Origin.CONFIRMATION_ATTEMPT || status != Status.RESTORE_PENDING) {
            throw new IllegalStateException("only a pending confirmation attempt can be abandoned");
        }
        return new OrderStockRestoration(id, orderId, paymentId, stockReservationId, claimId, Origin.ABANDONED_CONFIRMATION, status,
            holdCode, attempts, nextAttemptAt, restoredAt, createdAt);
    }

    /** 스윕 선점 — 시도를 하나 세고 다음 시각을 {@code min(maxBackoff, initialBackoff × 2^(시도-1))} 뒤로 미룬다. 선점이 곧 lease 다. */
    public OrderStockRestoration claimAttempt(Instant now, Duration initialBackoff, Duration maxBackoff) {
        if (status != Status.RESTORE_PENDING) {
            throw new IllegalStateException("only pending restoration can be claimed: status=" + status);
        }
        if (initialBackoff.isZero() || initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0
            || initialBackoff.compareTo(Duration.ofNanos(1000)) < 0 || now.isBefore(nextAttemptAt)) {
            throw new IllegalArgumentException("positive backoff and due restoration required");
        }
        int next = Math.addExact(attempts, 1);
        Duration backoff = initialBackoff.multipliedBy(1L << Math.min(next - 1, 20));
        if (backoff.compareTo(maxBackoff) > 0) {
            backoff = maxBackoff;
        }
        return with(status, null, next, now.plus(backoff), null);
    }

    /** 다시 불러도 같은 거절 · 재검증 불일치 — 사람이 볼 때까지 다시 집지 않는다. */
    public OrderStockRestoration hold(String code) {
        if (status != Status.RESTORE_PENDING) {
            throw new IllegalStateException("only pending restoration can be held: status=" + status);
        }
        return with(Status.HELD, code, attempts, null, null);
    }

    private OrderStockRestoration with(Status newStatus, String newHoldCode, int newAttempts, Instant newNextAttemptAt, Instant newRestoredAt) {
        return new OrderStockRestoration(id, orderId, paymentId, stockReservationId, claimId, origin, newStatus, newHoldCode, newAttempts,
            newNextAttemptAt, newRestoredAt, createdAt);
    }

    public boolean isNew() {
        return createdAt == null;
    }

    /** 복원 의도가 생긴 곳. 더하기만 한다 — Hibernate enum check 가 값 목록을 가진다. */
    public enum Origin {
        /** 결제 반영 기록({@code order_payment.restoration_claim_id}) — 확정 뒤 취소 · 미승인 거절. */
        PAYMENT_RECORD,
        CONFIRMATION_ATTEMPT,
        ABANDONED_CONFIRMATION
    }

    /** 작업 상태. 더하기만 한다. */
    public enum Status {
        RESTORE_PENDING, RESTORED, HELD;

        /** 스윕이 다음 시각에 집는 상태인가. */
        public boolean pending() {
            return this == RESTORE_PENDING;
        }
    }
}
