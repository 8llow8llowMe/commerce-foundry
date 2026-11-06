package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import com.commercefoundry.shared.commerce.Money;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 주문에 속한 클레임 — 반품 접수(RETURN)와 결제 후 고객 취소(CANCELLATION, #16 PR-2). 예상 환불액 null은 미산정이며 0원과 다르다.
 *
 * <p>취소 클레임의 수명은 {@code REQUESTED}(환불 대기) → {@code REFUNDED}(재고 복원 대기) → {@code COMPLETED} 이고, 종결 실패는
 * {@code REFUND_FAILED} · {@code RESTORATION_FAILED} 다. 상태는 아래 도메인 메서드로만 바꾼다. 환불 · 복원 호출은 이 클레임 아이디를 멱등 키로 쓴다.
 * 종결 실패의 운영자 수습(#17 PR-A)은 재시도 {@link #reopen} · 거절 {@link #reject}(REJECTED) · 수동 환불 확정 {@link #confirmRefundByAdmin} ·
 * 복원 생략 종결 {@link #completeByAdmin} 이다.
 *
 * @param paymentId 환불할 결제 아이디 (외부 FK: payment-service payment.id). 취소 클레임에만 있다
 * @param attemptCount 지금 단계(환불 또는 복원)의 미확정 재시도 횟수. 단계가 넘어가면 0 으로 돌아간다
 * @param nextAttemptAt 재시도 스케줄러가 다시 집을 시각. 종결 상태면 null
 * @param failureCode 종결 실패 사유 코드(원격 resultCode 등, 40자 이하). 종결 실패가 아니면 null
 * @param decision 운영자의 마지막 수습 결정(거절 · 수동 환불 확정 · 복원 생략 종결, #17). 운영자가 결정한 적 없으면 null. 거절(REJECTED)이면 반드시 있다
 * @param requestedAt 저장된 시각(읽기 전용). 저장 전이면 null
 * @param version 낙관적 락 버전. 저장 전이면 null
 */
public record Claim(
    long id, long orderId, UUID requestKey, Type type, Status status, Money expectedRefund, Long paymentId, int attemptCount, Instant nextAttemptAt,
    String failureCode, Decision decision, List<ClaimItem> items, Instant requestedAt, Long version
) {

    /** {@code claim.failure_code} 길이와 같다. */
    public static final int FAILURE_CODE_MAX_LENGTH = 40;

    /**
     * 환불 재시도 상한을 넘었다 — 결과를 끝내 확인하지 못해 REFUND_FAILED 로 넘긴 실패 코드 (#16 PR-2). PG 에서는 실제로 취소됐을 수 있으므로
     * 이 코드일 때만 늦게 온 환불 성공을 받는다({@link #markRefunded}).
     */
    public static final String REFUND_UNCONFIRMED = "REFUND_UNCONFIRMED";

    /** 재고 복원 재시도 상한을 넘었다 — 결과를 확인하지 못해 RESTORATION_FAILED 로 넘긴 실패 코드 (#16 PR-2). 운영자가 재고를 확인한다. */
    public static final String RESTORATION_UNCONFIRMED = "RESTORATION_UNCONFIRMED";

    public Claim {
        if (id <= 0 || orderId <= 0 || (version != null && version < 0) || (paymentId != null && paymentId <= 0) || attemptCount < 0) {
            throw new IllegalArgumentException("Invalid claim identifiers");
        }
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        items = items.stream().sorted(Comparator.comparingLong(ClaimItem::orderItemId)).toList();
        if (items.isEmpty() || items.size() > 50 || items.stream().map(ClaimItem::orderItemId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("Invalid claim items");
        }
        if (failureCode != null && (failureCode.isBlank() || failureCode.length() > FAILURE_CODE_MAX_LENGTH)) {
            throw new IllegalArgumentException("Invalid claim failure code");
        }
        // DB 의 ck_claim_rejected_decision 과 같다 — 거절은 누가 · 언제 · 왜 했는지 없이 남기지 않는다
        if (status == Status.REJECTED && decision == null) {
            throw new IllegalArgumentException("거절된 클레임에는 운영자 결정(사유 · 결정자 · 시각)이 있어야 합니다");
        }
        // DB 의 ck_claim_cancellation_payment · ck_claim_payment_cancellation_only · ck_claim_cancellation_next_attempt ·
        // ck_claim_cancellation_failure_code 와 같다. 항목 사유와 유형의 짝은 도메인만 본다
        if (type == Type.CANCELLATION) {
            if (paymentId == null || expectedRefund == null || expectedRefund.isZero()) {
                throw new IllegalArgumentException("취소 클레임에는 결제 아이디와 0보다 큰 환불액이 있어야 합니다");
            }
            if (status.isAwaitingAttempt() != (nextAttemptAt != null) || status.isFailed() != (failureCode != null)) {
                throw new IllegalArgumentException("취소 클레임의 다음 시도 시각은 대기 상태에만, 실패 코드는 종결 실패에만 있습니다: status=" + status);
            }
            if (items.stream().anyMatch(item -> !item.reason().isCancellationReason())) {
                throw new IllegalArgumentException("취소 클레임 항목은 취소 사유만 씁니다");
            }
        } else {
            if (paymentId != null) {
                throw new IllegalArgumentException("결제 아이디는 취소 클레임에만 둡니다");
            }
            if (items.stream().anyMatch(item -> !item.reason().isReturnReason())) {
                throw new IllegalArgumentException("반품 접수 항목은 반품 사유만 씁니다");
            }
        }
    }

    /** 운영자 결정 없이 만든다 — 접수 · 원격 결과 반영 · 읽기(결정 칸이 빈 행)가 쓴다. */
    public Claim(
        long id, long orderId, UUID requestKey, Type type, Status status, Money expectedRefund, Long paymentId, int attemptCount, Instant nextAttemptAt,
        String failureCode, List<ClaimItem> items, Instant requestedAt, Long version
    ) {
        this(id, orderId, requestKey, type, status, expectedRefund, paymentId, attemptCount, nextAttemptAt, failureCode, null, items, requestedAt, version);
    }

    /** 반품 접수 — 결제 아이디 · 재시도 칸 없이 만든다. */
    public Claim(long id, long orderId, UUID requestKey, Type type, Status status, Money expectedRefund, List<ClaimItem> items, Instant requestedAt, Long version) {
        this(id, orderId, requestKey, type, status, expectedRefund, null, 0, null, null, items, requestedAt, version);
    }

    /** 주문당 전체 취소 클레임은 하나다 — 같은 주문이면 언제 만들어도 같은 키다({@code uk_claim_order_id_request_key} 가 DB 에서 보장한다). */
    public static UUID cancellationRequestKey(long orderId) {
        return UUID.nameUUIDFromBytes(("cancellation:" + orderId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 결제 후 고객 전체 취소 클레임 (#16 PR-2) — 주문의 전 항목 · 전 수량을 환불 대기(REQUESTED)로 접수한다. 항목 수량은 복원할 SKU 수량이자
     * 부분 취소의 "이미 취소된 수량" 근거다. 주문 상태 판정(PAID · MAKING · PREPARING)은 부르는 쪽이 먼저 한다.
     *
     * @param refund 환불액 — {@link CancellationRefundPolicy} 로 계산한 값. 0보다 크고 주문 총액 이하, 주문 통화
     * @param now 접수 시각
     * @param lease 접수한 요청이 직접 환불을 끝낼 시간({@code order.cancellation.retry.lease}). 다음 시도 시각은 {@code now + lease} 다 —
     *     요청 처리 중인 클레임을 스케줄러가 바로 집어 같은 환불을 겹쳐 보내지 않게 하고, 요청이 끊기면 그 뒤에 스케줄러가 이어받는다
     */
    public static Claim requestCancellation(
        long id, Order order, long paymentId, Money refund, OrderCancelReason reason, LongSupplier itemIdGenerator, Instant now, Duration lease
    ) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("positive lease required");
        }
        Instant nextAttemptAt = now.plus(lease);
        ClaimItem.Reason itemReason = ClaimItem.Reason.of(reason);
        if (order.isNew() || refund.currency() != order.quote().currency() || refund.isGreaterThan(order.quote().totalAmount())) {
            throw new IllegalArgumentException("취소 클레임은 저장된 주문에서 주문 통화 · 총액 이하 환불액으로 만듭니다: orderId=" + order.id());
        }
        List<OrderQuoteLine> lines = order.quote().lines();
        List<ClaimItem> items = IntStream.range(0, lines.size())
            .mapToObj(index -> new ClaimItem(itemIdGenerator.getAsLong(), order.itemIds().get(index), lines.get(index).quantity(), itemReason)).toList();
        return new Claim(id, order.id(), cancellationRequestKey(order.id()), Type.CANCELLATION, Status.REQUESTED, refund, paymentId, 0,
            nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null, items, null, null);
    }

    /**
     * 환불이 확정됐다 — REQUESTED → REFUNDED(재고 복원 대기). 재시도 횟수는 복원 단계용으로 0 부터 다시 센다. 이미 REFUNDED 면 그대로다.
     * 재시도 상한을 넘어 미확정으로 닫은 REFUND_FAILED({@link #REFUND_UNCONFIRMED})도 받는다 — 늦게 온 성공이 실제 결과다. 다른 실패 코드(종결 거절)는 받지 않는다.
     */
    public Claim markRefunded(Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireCancellation();
        if (status == Status.REFUNDED) {
            return this;
        }
        if (!isNew() && status == Status.REFUND_FAILED && REFUND_UNCONFIRMED.equals(failureCode)) {
            return with(Status.REFUNDED, 0, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null);
        }
        requireStatus(Status.REQUESTED, Status.REFUNDED);
        return with(Status.REFUNDED, 0, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null);
    }

    /** 재고 복원까지 끝났다 — REFUNDED → COMPLETED. 이미 COMPLETED 면 그대로다. */
    public Claim complete() {
        requireCancellation();
        if (status == Status.COMPLETED) {
            return this;
        }
        requireStatus(Status.REFUNDED, Status.COMPLETED);
        return with(Status.COMPLETED, attemptCount, null, null);
    }

    /** 환불이 종결 거절됐다(또는 재시도 상한을 넘었다) — REQUESTED → REFUND_FAILED. 재고는 복원하지 않는다. */
    public Claim failRefund(String failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");
        requireCancellation();
        requireStatus(Status.REQUESTED, Status.REFUND_FAILED);
        return with(Status.REFUND_FAILED, attemptCount, null, failureCode);
    }

    /** 재고 복원이 종결 거절됐다 — REFUNDED → RESTORATION_FAILED. 환불은 이미 확정이라 되돌리지 않는다. */
    public Claim failRestoration(String failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");
        requireCancellation();
        requireStatus(Status.REFUNDED, Status.RESTORATION_FAILED);
        return with(Status.RESTORATION_FAILED, attemptCount, null, failureCode);
    }

    /**
     * 결과가 미확정이라 나중에 같은 클레임 아이디 · 같은 본문으로 다시 보낸다 — 상태는 그대로(REQUESTED · REFUNDED), 횟수 +1,
     * 다음 시각은 {@code now + min(maxBackoff, initialBackoff × 2^지금 횟수)} 다. 재시도 상한 판정은 부르는 쪽이 한다.
     */
    public Claim retryLater(Instant now, Duration initialBackoff, Duration maxBackoff) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        requireCancellation();
        if (isNew() || (status != Status.REQUESTED && status != Status.REFUNDED)) {
            throw new IllegalStateException("재시도는 저장된 환불 대기 · 복원 대기 클레임만 합니다: status=" + status);
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("backoff must be positive and max >= initial");
        }
        // 두 배씩 늘리다 상한에 닿으면 멈춘다 — Duration 끼리 비교하고 큰 횟수에서도 넘치지 않는다
        Duration backoff = initialBackoff;
        for (int doubled = 0; doubled < attemptCount && backoff.compareTo(maxBackoff) < 0; doubled++) {
            backoff = backoff.multipliedBy(2);
        }
        if (backoff.compareTo(maxBackoff) > 0) {
            backoff = maxBackoff;
        }
        return with(status, attemptCount + 1, now.plus(backoff).truncatedTo(ChronoUnit.MICROS), null);
    }

    /**
     * 재시도 스케줄러가 원격 호출 전에 이 클레임을 선점한다 — 상태 · 횟수는 그대로 두고 다음 시각만 {@code until} 로 미룬다 (#16 PR-2).
     * 저장(버전 비교)에서 이긴 워커만 호출하고, 그 사이 다른 워커 · 고객 요청은 이 시각까지 집지 않는다.
     */
    public Claim holdUntil(Instant until) {
        Objects.requireNonNull(until, "until");
        requireCancellation();
        if (isNew() || !status.isAwaitingAttempt()) {
            throw new IllegalStateException("선점은 저장된 환불 대기 · 복원 대기 클레임만 합니다: status=" + status);
        }
        return with(status, attemptCount, until.truncatedTo(ChronoUnit.MICROS), null);
    }

    /**
     * 운영자 재시도 (#17 PR-A) — 종결 실패를 그 단계의 대기 상태로 되돌린다: REFUND_FAILED → REQUESTED(환불 다시), RESTORATION_FAILED → REFUNDED(복원 다시).
     * 횟수는 0, 실패 코드는 비우고, 다음 시각은 {@code until}(lease) 이라 이 요청이 바로 잇는 동안 스케줄러가 집지 않는다. 운영자 결정 칸은 그대로 둔다.
     *
     * @throws IllegalStateException 저장 전 · 종결 실패가 아닌 상태 · 반품
     */
    public Claim reopen(Instant until) {
        Objects.requireNonNull(until, "until");
        requireCancellation();
        if (isNew() || !status.isFailed()) {
            throw new IllegalStateException("다시 열기는 저장된 종결 실패 클레임만 합니다: status=" + status);
        }
        Status reopened = status == Status.REFUND_FAILED ? Status.REQUESTED : Status.REFUNDED;
        return with(reopened, 0, until.truncatedTo(ChronoUnit.MICROS), null);
    }

    /**
     * 운영자 수동 환불 확정 (#17 PR-A) — 운영자가 PG · 결제 기록에서 환불을 확인했다. REFUND_FAILED(실패 코드 무관) → REFUNDED(재고 복원 대기, 횟수 0,
     * 다음 시각 {@code nextAttemptAt}). 이어서 복원 단계가 돈다.
     *
     * @throws IllegalStateException 저장 전 · REFUND_FAILED 가 아닌 상태 · 반품
     */
    public Claim confirmRefundByAdmin(Decision decision, Instant nextAttemptAt) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireCancellation();
        requireStatus(Status.REFUND_FAILED, Status.REFUNDED);
        return with(Status.REFUNDED, 0, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null, decision);
    }

    /**
     * 운영자 거절 (#17 PR-A) — 환불이 종결 거절된 취소를 받아들이지 않는다. REFUND_FAILED → REJECTED(다음 시각 · 실패 코드 없음). 미확정으로 닫힌
     * 클레임({@link #REFUND_UNCONFIRMED})은 PG 에서 실제로 취소됐을 수 있어 거절하지 않는다 — 돈은 나갔는데 주문이 살아나는 일을 막는다.
     *
     * @throws IllegalStateException 저장 전 · REFUND_FAILED 가 아닌 상태 · 미확정 실패 · 반품
     */
    public Claim reject(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        requireCancellation();
        requireStatus(Status.REFUND_FAILED, Status.REJECTED);
        if (isRefundUnconfirmed()) {
            throw new IllegalStateException("환불 결과를 확인하지 못한 클레임은 거절하지 않습니다: claimId=" + id);
        }
        return with(Status.REJECTED, attemptCount, null, null, decision);
    }

    /**
     * 운영자 종결 (#17 PR-A) — 재고 복원이 종결 실패한 클레임을 복원 없이 끝낸다. RESTORATION_FAILED → COMPLETED. 재고는 운영자가 재고 조정으로 먼저 맞춘다.
     *
     * @throws IllegalStateException 저장 전 · RESTORATION_FAILED 가 아닌 상태 · 반품
     */
    public Claim completeByAdmin(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        requireCancellation();
        requireStatus(Status.RESTORATION_FAILED, Status.COMPLETED);
        return with(Status.COMPLETED, attemptCount, null, null, decision);
    }

    /** 환불 재시도 상한을 넘어 결과를 모른 채 닫힌 클레임인가 — 거절할 수 없고 늦은 성공을 받는다. */
    public boolean isRefundUnconfirmed() {
        return status == Status.REFUND_FAILED && REFUND_UNCONFIRMED.equals(failureCode);
    }

    public boolean isNew() {
        return version == null;
    }

    private void requireCancellation() {
        if (type != Type.CANCELLATION) {
            throw new IllegalStateException("취소 클레임만 이 전이를 합니다: type=" + type);
        }
    }

    private void requireStatus(Status expected, Status next) {
        if (isNew() || status != expected) {
            throw new IllegalStateException("클레임 상태 전이가 허용되지 않습니다: %s → %s".formatted(status, next));
        }
    }

    private Claim with(Status nextStatus, int nextAttemptCount, Instant nextAttempt, String nextFailureCode) {
        return with(nextStatus, nextAttemptCount, nextAttempt, nextFailureCode, decision);
    }

    private Claim with(Status nextStatus, int nextAttemptCount, Instant nextAttempt, String nextFailureCode, Decision nextDecision) {
        return new Claim(id, orderId, requestKey, type, nextStatus, expectedRefund, paymentId, nextAttemptCount, nextAttempt, nextFailureCode, nextDecision,
            items, requestedAt, version);
    }

    /**
     * 운영자의 수습 결정 (#17 PR-A) — 사유 · 결정한 관리자 · 시각. 감사 줄과 주문 상태 이력(ADMIN)에도 남지만, 클레임 화면이 바로 보여 주려고 클레임에도 둔다.
     * 결정이 다시 나면(예: 수동 환불 확정 뒤 복원 생략 종결) 마지막 결정으로 덮는다 — 앞선 결정은 감사 줄 · 주문 이력에 있다.
     *
     * @param reason 사유 (1~200자, 공백만은 안 된다) — {@code claim.decision_reason} 길이와 같다
     * @param decidedByMemberId 결정한 관리자 회원 아이디
     * @param decidedAt 결정 시각 — DB 정밀도(마이크로초)로 자른다
     */
    public record Decision(String reason, long decidedByMemberId, Instant decidedAt) {

        public static final int REASON_MAX_LENGTH = 200;

        public Decision {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(decidedAt, "decidedAt");
            if (reason.isBlank() || reason.length() > REASON_MAX_LENGTH || decidedByMemberId <= 0) {
                throw new IllegalArgumentException("Invalid claim decision");
            }
            decidedAt = decidedAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Type implements CodeNameDescribable {
        RETURN("반품", "배송된 상품의 반품 요청입니다."),
        CANCELLATION("취소", "결제 뒤 출고 전 주문의 취소 요청입니다.");

        private final String displayName;
        private final String description;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Status implements CodeNameDescribable {
        REQUESTED("접수", "클레임이 접수되었습니다. 환불은 아직 실행되지 않았습니다."),
        REFUNDED("환불 완료", "환불이 확정되었습니다. 재고 복원을 기다립니다."),
        COMPLETED("완료", "환불과 재고 복원이 끝났습니다."),
        REFUND_FAILED("환불 실패", "환불이 거절되었습니다. 운영자 확인이 필요합니다."),
        RESTORATION_FAILED("재고 복원 실패", "환불은 확정되었지만 재고 복원이 거절되었습니다. 운영자 확인이 필요합니다."),
        /** 반품 승인(검수 완료) — #17 PR-B 가 전이를 만든다. 지금은 DDL check · 조회 필터에만 있다. */
        APPROVED("승인", "운영자가 클레임을 승인했습니다. 환불을 진행합니다."),
        REJECTED("거절", "운영자가 클레임을 거절했습니다.");

        private final String displayName;
        private final String description;

        /** 다음 시도를 기다리는 상태(환불 대기 · 복원 대기)인가 — 취소 클레임이면 이때만 다음 시도 시각이 있다. */
        public boolean isAwaitingAttempt() {
            return this == REQUESTED || this == REFUNDED;
        }

        /** 종결 실패인가 — 취소 클레임이면 이때만 실패 코드가 있다. */
        public boolean isFailed() {
            return this == REFUND_FAILED || this == RESTORATION_FAILED;
        }
    }
}
