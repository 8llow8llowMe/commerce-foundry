package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import com.commercefoundry.shared.commerce.Money;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 *
 * <p>반품 클레임의 수명 (#17 B1 저장 기반) — {@code REQUESTED}(접수 · 검수 대기) → 운영자 승인 {@code APPROVED}(환불 대기) 또는 거절 {@code REJECTED}(끝).
 * 환불 대기를 뜻하는 상태가 유형마다 다르다 — 취소는 {@code REQUESTED}, 반품은 {@code APPROVED} 다. 그래서 대기 판정은 상태가 아니라 클레임
 * ({@link #isAwaitingAttempt()} · {@link #isAwaitingRefund()})이 한다. 승인 뒤 환불 · 복원 전이는 취소와 같은 메서드다(#17 B2a) — 환불 대기 자리만
 * 유형별({@link #awaitingRefundStatus()})이고, {@code restock=false} 반품은 복원 없이 REFUNDED → COMPLETED 다({@link #requiresRestoration()}).
 * 다시 열기 · 운영자 수습 결정({@link #reopen} · {@link #confirmRefundByAdmin} · {@link #completeByAdmin})은 아직 취소만이다(#17 B2b).
 *
 * @param paymentId 환불할 결제 아이디 (외부 FK: payment-service payment.id). 취소 클레임과 승인된 반품에만 있다
 * @param attemptCount 지금 단계(환불 또는 복원)의 미확정 재시도 횟수. 단계가 넘어가면 0 으로 돌아간다
 * @param nextAttemptAt 재시도 스케줄러가 다시 집을 시각. 대기 상태({@link #isAwaitingAttempt()})에만 있다
 * @param failureCode 종결 실패 사유 코드(원격 resultCode 등, 40자 이하). 종결 실패가 아니면 null
 * @param approval 반품 승인 기록 — 승인 이후 반품(REQUESTED · REJECTED 가 아닌 반품)에만 있다
 * @param requestedAt 저장된 시각(읽기 전용). 저장 전이면 null
 * @param version 낙관적 락 버전. 저장 전이면 null
 */
public record Claim(
    long id, long orderId, UUID requestKey, Type type, Status status, Money expectedRefund, Long paymentId, int attemptCount, Instant nextAttemptAt,
    String failureCode, Decision decision, ReturnApproval approval, List<ClaimItem> items, Instant requestedAt, Long version
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

    private static final Set<Status> RETURN_QUANTITY_RESERVING_STATUSES = Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(Status.REJECTED)));

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
        // 다음 시도 시각은 대기 상태에만, 실패 코드는 종결 실패에만 — DB 의 ck_claim_cancellation_next_attempt · ck_claim_return_next_attempt ·
        // ck_claim_cancellation_failure_code · ck_claim_return_failure_code 와 같다
        if (type.isAwaitingAttempt(status) != (nextAttemptAt != null) || status.isFailed() != (failureCode != null)) {
            throw new IllegalArgumentException("클레임의 다음 시도 시각은 대기 상태에만, 실패 코드는 종결 실패에만 있습니다: type=%s, status=%s".formatted(type, status));
        }
        // DB 의 ck_claim_cancellation_payment · ck_claim_cancellation_not_approved · ck_claim_return_approval 과 같다. 항목 사유와 유형의 짝은 도메인만 본다
        if (type == Type.CANCELLATION) {
            if (paymentId == null || expectedRefund == null || expectedRefund.isZero()) {
                throw new IllegalArgumentException("취소 클레임에는 결제 아이디와 0보다 큰 환불액이 있어야 합니다");
            }
            if (status == Status.APPROVED || approval != null) {
                throw new IllegalArgumentException("취소 클레임은 반품 승인 상태 · 기록을 두지 않습니다");
            }
            if (items.stream().anyMatch(item -> !item.reason().isCancellationReason())) {
                throw new IllegalArgumentException("취소 클레임 항목은 취소 사유만 씁니다");
            }
        } else {
            if (items.stream().anyMatch(item -> !item.reason().isReturnReason())) {
                throw new IllegalArgumentException("반품 접수 항목은 반품 사유만 씁니다");
            }
            requireReturnApprovalColumns(status, expectedRefund, paymentId, approval);
        }
        requireDecisionShape(type, status, decision);
    }

    /**
     * 운영자 결정 — DB 의 {@code ck_claim_admin_decision} 과 같다. 접수(REQUESTED) 반품에는 결정이 없고, 직전 실패 코드 없는 결정은 거절(REJECTED)에만 있다.
     * 반품 결정(거절 사유 등)을 만드는 전이는 #17 B2 다 — B1 은 B2 가 쓴 행을 읽을 수만 있게 해 B2 → B1 롤백 때 매퍼가 깨지지 않게 한다.
     */
    private static void requireDecisionShape(Type type, Status status, Decision decision) {
        if (decision == null) {
            return;
        }
        if (type == Type.RETURN && status == Status.REQUESTED) {
            throw new IllegalArgumentException("접수 반품에는 운영자 결정이 없습니다");
        }
        if (decision.previousFailureCode() == null && status != Status.REJECTED) {
            throw new IllegalArgumentException("직전 실패 코드 없는 결정은 거절 클레임에만 있습니다: status=" + status);
        }
    }

    /** 반품 — 승인 전(REQUESTED) · 거절(REJECTED)이면 결제 아이디 · 승인 기록이 없고, 승인 이후면 둘 다 있고 환불액이 0보다 크다. */
    private static void requireReturnApprovalColumns(Status status, Money expectedRefund, Long paymentId, ReturnApproval approval) {
        if (status == Status.REQUESTED || status == Status.REJECTED) {
            if (paymentId != null || approval != null) {
                throw new IllegalArgumentException("승인 전 · 거절된 반품은 결제 아이디 · 승인 기록을 두지 않습니다: status=" + status);
            }
            return;
        }
        if (paymentId == null || approval == null || expectedRefund == null || expectedRefund.isZero()) {
            throw new IllegalArgumentException("승인된 반품에는 결제 아이디 · 승인 기록 · 0보다 큰 환불액이 있어야 합니다: status=" + status);
        }
        if (approval.deduction().currency() != expectedRefund.currency()) {
            throw new IllegalArgumentException("반품 차감액과 환불액의 통화가 다릅니다");
        }
    }

    /** 승인 기록 없는 생성자 — 결정까지 싣는 기존 호출부(매퍼 · 저장 반환)의 호환을 유지한다. */
    public Claim(long id, long orderId, UUID requestKey, Type type, Status status, Money expectedRefund, Long paymentId, int attemptCount,
        Instant nextAttemptAt, String failureCode, Decision decision, List<ClaimItem> items, Instant requestedAt, Long version) {
        this(id, orderId, requestKey, type, status, expectedRefund, paymentId, attemptCount, nextAttemptAt, failureCode, decision, null, items, requestedAt,
            version);
    }

    /** 기존 취소 생성자와 저장된 결정 없는 행의 호환을 유지한다. */
    public Claim(long id, long orderId, UUID requestKey, Type type, Status status, Money expectedRefund, Long paymentId, int attemptCount,
        Instant nextAttemptAt, String failureCode, List<ClaimItem> items, Instant requestedAt, Long version) {
        this(id, orderId, requestKey, type, status, expectedRefund, paymentId, attemptCount, nextAttemptAt, failureCode, null, null, items, requestedAt,
            version);
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
     * 환불이 확정됐다 — 환불 대기(취소 REQUESTED · 반품 APPROVED) → REFUNDED(재고 복원 대기). 재시도 횟수는 복원 단계용으로 0 부터 다시 센다. 이미 REFUNDED 면 그대로다.
     * 재시도 상한을 넘어 미확정으로 닫은 REFUND_FAILED({@link #REFUND_UNCONFIRMED})도 받는다 — 늦게 온 성공이 실제 결과다. 다른 실패 코드(종결 거절)는 받지 않는다.
     */
    public Claim markRefunded(Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (status == Status.REFUNDED) {
            return this;
        }
        if (!isNew() && status == Status.REFUND_FAILED && REFUND_UNCONFIRMED.equals(failureCode)) {
            return with(Status.REFUNDED, 0, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null);
        }
        requireStatus(awaitingRefundStatus(), Status.REFUNDED);
        return with(Status.REFUNDED, 0, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null);
    }

    /** 동일 환불 요청의 확정 성공 증거 전용. 실패 코드보다 성공을 우선하며 일반 markRefunded 계약은 유지한다. 환불 대기 자리는 유형별이다. */
    public Claim markConfirmedRefund(Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (isNew() || (!isAwaitingRefund() && status != Status.REFUND_FAILED)) {
            throw new IllegalStateException("확정 환불은 저장된 환불 대기 · 실패에만 반영합니다");
        }
        return with(Status.REFUNDED, 0, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null);
    }

    /** 버전 · lease · 단계가 바뀌어도 같은 원격 환불 요청인지 확인한다. 유형이 다르면 다른 요청이다. */
    public boolean hasSameRefundRequest(Claim other) {
        return other != null && other.type == type && id == other.id && orderId == other.orderId
            && requestKey.equals(other.requestKey) && Objects.equals(paymentId, other.paymentId) && Objects.equals(expectedRefund, other.expectedRefund);
    }

    public boolean hasRecordedRefund() {
        return status == Status.REFUNDED || status == Status.RESTORATION_FAILED || status == Status.COMPLETED;
    }

    /** 반품 수량을 잡아 두는 상태 — 거절(REJECTED)만 뺀 전부다. 영속 어댑터의 수량 합산이 이 집합을 그대로 쓴다 (#17 B1). */
    public static Set<Status> returnQuantityReservingStatuses() {
        return RETURN_QUANTITY_RESERVING_STATUSES;
    }

    /**
     * 이 반품이 고객 반품 접수의 "이미 반품에 잡힌 수량"에 들어가는가 — 거절만 빠진다. 환불 실패는 수동 확정으로 환불될 수 있고 복원 실패는 환불이 이미 나갔다 —
     * 빼면 같은 수량을 다시 접수해 이중 환불이 된다. {@link #isSettledReturn()} 또는 {@link #isOpenReturn()} 과 같다. 취소 클레임은 아니다.
     */
    public boolean reservesReturnQuantity() {
        return type == Type.RETURN && RETURN_QUANTITY_RESERVING_STATUSES.contains(status);
    }

    /** 환불이 기록된(정산된) 반품인가 — REFUNDED · RESTORATION_FAILED · COMPLETED. 반품 환불 산식의 "이미 정산한 반품"과 반품 완료 판정이 이것만 센다. */
    public boolean isSettledReturn() {
        return type == Type.RETURN && hasRecordedRefund();
    }

    /** 아직 끝나지 않은 반품인가 — 접수(REQUESTED) · 승인(APPROVED) · 환불 실패(REFUND_FAILED). 하나라도 있으면 주문은 반품 요청에 머문다. */
    public boolean isOpenReturn() {
        return type == Type.RETURN && (status == Status.REQUESTED || isReturnRefundInFlight());
    }

    /**
     * 승인 뒤 환불이 끝나지 않은 반품인가 — APPROVED · REFUND_FAILED. 환불액이 정해졌지만 정산(환불 기록) 전이라, 이런 반품이 있으면 같은 주문의 다른 반품
     * 환불액을 계산하지 않는다({@link ReturnRefundPolicy.Settled#of}).
     */
    public boolean isReturnRefundInFlight() {
        return type == Type.RETURN && (status == Status.APPROVED || status == Status.REFUND_FAILED);
    }

    /** 다음 시도를 기다리는 상태(환불 대기 · 복원 대기)인가 — 이때만 다음 시도 시각이 있다. 환불 대기는 취소면 REQUESTED, 반품이면 APPROVED 다. */
    public boolean isAwaitingAttempt() {
        return type.isAwaitingAttempt(status);
    }

    /** 환불을 기다리는가 — 취소는 REQUESTED, 반품은 승인된 APPROVED 다. 승인 전 반품(REQUESTED)은 환불 대상이 아직 아니다. */
    public boolean isAwaitingRefund() {
        return status == awaitingRefundStatus();
    }

    /** 이 유형의 환불 대기 상태 — 취소 REQUESTED, 반품 APPROVED. 환불 확정 · 실패 · 재시도 상한이 이 자리에서 출발한다. */
    public Status awaitingRefundStatus() {
        return type.awaitingRefundStatus();
    }

    /**
     * 환불 뒤 재고 복원을 부르는가 — 취소는 늘, 반품은 승인 때 재고로 되돌림({@link ReturnApproval#restock()})을 고른 경우만이다 (#17 B2a).
     * 부르지 않는 반품은 환불 기록 때 REFUNDED 를 거쳐 바로 COMPLETED 다.
     */
    public boolean requiresRestoration() {
        return type == Type.CANCELLATION || (approval != null && approval.restock());
    }

    /**
     * 결제 취소 키 유효 기간(14일)을 세는 기준 시각 — 환불을 처음 보내는 쪽이 같은 claimId 로 키를 여는 시각이다. 취소는 접수 시각, 반품은 승인 시각이다
     * (반품은 검수 뒤 승인할 때 처음 환불을 보낸다).
     *
     * @throws IllegalStateException 저장 전 취소 · 승인 전 반품 — 아직 기준이 없다
     */
    public Instant refundDeadlineBase() {
        Instant base = type == Type.CANCELLATION ? requestedAt : approval == null ? null : approval.approvedAt();
        if (base == null) {
            throw new IllegalStateException("환불 기한 기준이 아직 없습니다: type=%s, status=%s".formatted(type, status));
        }
        return base;
    }

    /**
     * 반품 승인 (#17 B1) — 검수를 마친 저장된 반품 접수(REQUESTED)를 환불 대기(APPROVED)로 바꾼다. 환불액 · 차감액 · 통화는 정책 결과
     * {@link ReturnRefundPolicy.Refund} 하나에서 꺼낸다(환불액 = 정책 금액 − 차감액). 결제 아이디 · 승인 기록을 싣고 횟수 0, 다음 시각 {@code now + lease} 로
     * 둔다 — {@link #requestCancellation} 과 같이 승인한 요청이 직접 환불을 끝낼 시간이다. 승인 가능 여부(주문 상태 · 진행 중 반품 409)는 부르는 쪽(B2)이 먼저 본다.
     *
     * @param order 이 반품의 저장된 주문 — 통화 · 총액 · 항목을 대조한다
     * @param refund {@link ReturnRefundPolicy#refund} 결과에 차감액을 적용한 값. 0 이하면 {@link ReturnRefundPolicy.Refund#toMoney()} 가 거부한다
     * @param restock 검수 뒤 재고로 되돌릴지
     * @param now 승인 시각 — 반품 환불의 결제 취소 키 기한 기준이다
     * @param lease 승인한 요청이 직접 환불을 끝낼 시간({@code order.cancellation.retry.lease})
     * @throws IllegalStateException 반품이 아님 · 저장된 REQUESTED 가 아님 · 0 이하 환불액
     * @throws IllegalArgumentException 다른 주문 · 주문에 없는 항목 · 다른 통화 · 결제 총액을 넘는 정책 금액 · 0 이하 lease
     */
    public Claim approveReturn(
        Order order, long paymentId, ReturnRefundPolicy.Refund refund, boolean restock, long approvedByMemberId, Instant now, Duration lease
    ) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        requireReturn();
        requireStatus(Status.REQUESTED, Status.APPROVED);
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("positive lease required");
        }
        if (order.isNew() || order.id() != orderId || !order.itemIds().containsAll(items.stream().map(ClaimItem::orderItemId).toList())) {
            throw new IllegalArgumentException("반품 승인은 이 반품의 저장된 주문으로 합니다: claimId=%d, orderId=%d".formatted(id, order.id()));
        }
        Money amount = refund.toMoney();
        if (refund.currency() != order.quote().currency() || refund.policyAmount() > order.quote().totalAmount().amount()) {
            throw new IllegalArgumentException("반품 환불은 주문 통화 · 결제 총액 이하여야 합니다: claimId=" + id);
        }
        ReturnApproval approval = new ReturnApproval(restock, refund.deductionMoney(), approvedByMemberId, now);
        return new Claim(id, orderId, requestKey, type, Status.APPROVED, amount, paymentId, 0, now.plus(lease).truncatedTo(ChronoUnit.MICROS), null, decision,
            approval, items, requestedAt, version);
    }

    /**
     * 반품 거절 (#17 B1) — 저장된 반품 접수(REQUESTED)를 거절(REJECTED, 끝)로 바꾼다. 환불 · 복원이 없고 수량 합산에서 빠진다.
     * 거절 사유 · 결정자 기록과 주문 상태 정리({@link Order#settleReturn})는 호출부(B2)가 정한다.
     */
    public Claim rejectReturn() {
        requireReturn();
        requireStatus(Status.REQUESTED, Status.REJECTED);
        return with(Status.REJECTED, attemptCount, null, null);
    }

    /** 재고 복원까지 끝났다(또는 복원하지 않는 반품의 환불 기록) — REFUNDED → COMPLETED. 이미 COMPLETED 면 그대로다. */
    public Claim complete() {
        if (status == Status.COMPLETED) {
            return this;
        }
        requireStatus(Status.REFUNDED, Status.COMPLETED);
        return with(Status.COMPLETED, attemptCount, null, null);
    }

    /** 환불이 종결 거절됐다(또는 재시도 상한을 넘었다) — 환불 대기(취소 REQUESTED · 반품 APPROVED) → REFUND_FAILED. 재고는 복원하지 않는다. */
    public Claim failRefund(String failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");
        requireStatus(awaitingRefundStatus(), Status.REFUND_FAILED);
        return with(Status.REFUND_FAILED, attemptCount, null, failureCode);
    }

    /** 재고 복원이 종결 거절됐다 — REFUNDED → RESTORATION_FAILED. 환불은 이미 확정이라 되돌리지 않는다. */
    public Claim failRestoration(String failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");
        requireStatus(Status.REFUNDED, Status.RESTORATION_FAILED);
        return with(Status.RESTORATION_FAILED, attemptCount, null, failureCode);
    }

    /**
     * 결과가 미확정이라 나중에 같은 클레임 아이디 · 같은 본문으로 다시 보낸다 — 상태는 그대로(환불 대기 · REFUNDED), 횟수 +1,
     * 다음 시각은 {@code now + min(maxBackoff, initialBackoff × 2^지금 횟수)} 다. 재시도 상한 판정은 부르는 쪽이 한다.
     */
    public Claim retryLater(Instant now, Duration initialBackoff, Duration maxBackoff) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (isNew() || !isAwaitingAttempt()) {
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
        if (isNew() || !isAwaitingAttempt()) {
            throw new IllegalStateException("선점은 저장된 환불 대기 · 복원 대기 클레임만 합니다: status=" + status);
        }
        return with(status, attemptCount, until.truncatedTo(ChronoUnit.MICROS), null);
    }

    /**
     * 운영자 재시도로 종결 실패를 그 단계의 대기 상태로 다시 연다 (#17 A2b) — REFUND_FAILED({@link #REFUND_UNCONFIRMED}) → REQUESTED, RESTORATION_FAILED → REFUNDED.
     * 실패 코드는 지우고 다음 시각을 {@code until}(선점 lease)로 둔다. 같은 클레임 아이디 · 저장된 결제 · 금액으로 다시 보내므로 원격은 멱등이다.
     *
     * <p>결과 미확인이 아닌 환불 실패(결제사의 종결 거절)는 다시 열지 않는다 — payment 는 같은 claimId 의 거절 원장 때문에 늘 {@code PAYMENT_016} 을 돌려주므로
     * 다시 보내도 진짜 거절 사유 코드만 지워진다. 그 건은 대표 관리자의 수동 확정(A3) 몫이다. 재시도 자격은 {@link ClaimAdminRecoveryPolicy#requireRetry} 가 먼저 본다.
     *
     * @param attemptCount 다시 연 단계의 시작 횟수 — 결제 취소 키의 유효 기간 안에서 자동 재시도가 끝나도록 부르는 쪽이 정한다(0 이상)
     */
    public Claim reopen(Instant until, int attemptCount) {
        Objects.requireNonNull(until, "until");
        requireCancellation();
        if (isNew() || !status.isFailed() || attemptCount < 0) {
            throw new IllegalStateException("다시 열기는 저장된 종결 실패 클레임만 합니다: status=" + status);
        }
        if (status == Status.REFUND_FAILED && !REFUND_UNCONFIRMED.equals(failureCode)) {
            throw new IllegalStateException("결제사가 거절한 환불은 다시 열지 않습니다: failureCode=" + failureCode);
        }
        Status reopened = status == Status.REFUND_FAILED ? Status.REQUESTED : Status.REFUNDED;
        return with(reopened, attemptCount, until.truncatedTo(ChronoUnit.MICROS), null);
    }

    /** 결제사의 종결 거절로 닫힌 환불 실패인가 — 결과 미확인({@link #REFUND_UNCONFIRMED})과 달리 다시 보내도 같은 거절이다 (#17). 반품도 같다(#17 B2a). */
    public boolean isRefundRejected() {
        return status == Status.REFUND_FAILED && !REFUND_UNCONFIRMED.equals(failureCode);
    }

    /**
     * 운영자 수동 환불 확정 (#17 A3) — 대표 관리자가 PG · 결제 기록에서 환불을 확인한 환불 실패를 REFUNDED(재고 복원 대기, 횟수 0, 다음 시각
     * {@code nextAttemptAt})로 바꾼다. 결정의 직전 실패 코드는 지금 실패 코드와 같아야 한다 — 실패 코드 칸은 비지만 근거는 결정에 남는다.
     */
    public Claim confirmRefundByAdmin(Decision decision, Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireDecisionOn(Status.REFUND_FAILED, decision);
        return new Claim(id, orderId, requestKey, type, Status.REFUNDED, expectedRefund, paymentId, 0, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), null,
            decision, approval, items, requestedAt, version);
    }

    /**
     * 운영자 복원 생략 종결 (#17 A3) — 재고 복원이 종결 실패한 클레임을 복원 없이 COMPLETED 로 닫는다. 재고는 운영자가 재고 조정으로 먼저 맞춘다.
     * 종결 자격(주문 취소 완료 · 확정 실패 코드)은 {@link ClaimAdminRecoveryPolicy#requireCompletion} 이 먼저 본다.
     */
    public Claim completeByAdmin(Decision decision) {
        requireDecisionOn(Status.RESTORATION_FAILED, decision);
        return new Claim(id, orderId, requestKey, type, Status.COMPLETED, expectedRefund, paymentId, attemptCount, null, null, decision, approval, items,
            requestedAt, version);
    }

    private void requireDecisionOn(Status expected, Decision decision) {
        Objects.requireNonNull(decision, "decision");
        requireCancellation();
        if (isNew() || status != expected || !Objects.equals(decision.previousFailureCode(), failureCode)) {
            throw new IllegalStateException("운영자 결정은 저장된 %s 클레임의 지금 실패 코드로만 남깁니다: status=%s".formatted(expected, status));
        }
    }

    public boolean isNew() {
        return version == null;
    }

    private void requireCancellation() {
        if (type != Type.CANCELLATION) {
            throw new IllegalStateException("취소 클레임만 이 전이를 합니다: type=" + type);
        }
    }

    private void requireReturn() {
        if (type != Type.RETURN) {
            throw new IllegalStateException("반품 클레임만 이 전이를 합니다: type=" + type);
        }
    }

    private void requireStatus(Status expected, Status next) {
        if (isNew() || status != expected) {
            throw new IllegalStateException("클레임 상태 전이가 허용되지 않습니다: %s → %s".formatted(status, next));
        }
    }

    private Claim with(Status nextStatus, int nextAttemptCount, Instant nextAttempt, String nextFailureCode) {
        return new Claim(id, orderId, requestKey, type, nextStatus, expectedRefund, paymentId, nextAttemptCount, nextAttempt, nextFailureCode, decision, approval,
            items, requestedAt, version);
    }

    /**
     * 반품 승인 기록 (#17 B1) — 검수 결과와 승인한 운영자. 환불액은 클레임의 {@code expectedRefund}(정책 금액 − 차감액)다.
     *
     * @param restock 검수 뒤 재고로 되돌릴지 — 되돌리지 않으면(불량 폐기 등) 재고 복원을 부르지 않는다(B2)
     * @param deduction 정책 금액에서 뺀 금액(반품 배송비 · 훼손 등, 0 이상). 환불액과 같은 통화
     * @param approvedByMemberId 승인한 관리자 아이디 (외부 FK: auth-service member.id)
     * @param approvedAt 승인 시각 — 반품 환불의 결제 취소 키 기한 기준({@link Claim#refundDeadlineBase()})이다. 마이크로초로 자른다
     */
    public record ReturnApproval(boolean restock, Money deduction, long approvedByMemberId, Instant approvedAt) {

        public ReturnApproval {
            Objects.requireNonNull(deduction, "deduction");
            Objects.requireNonNull(approvedAt, "approvedAt");
            if (approvedByMemberId <= 0) {
                throw new IllegalArgumentException("Invalid return approval");
            }
            approvedAt = approvedAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    /**
     * 마지막 수습 결정과 그 직전 실패. 이전 결정의 누적 감사는 후속 호출부가 담당한다. 사유에 개인정보를 넣지 않는다.
     *
     * @param previousFailureCode 결정 직전 실패 코드. 거절(REJECTED) 결정만 없을 수 있다(#17 B1 — DB {@code ck_claim_admin_decision} 과 같다. 클레임 생성자가 본다)
     */
    public record Decision(String reason, long decidedByMemberId, Instant decidedAt, String previousFailureCode) {
        public static final int REASON_MAX_LENGTH = 200;

        public Decision {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(decidedAt, "decidedAt");
            if (reason.isBlank() || reason.length() > REASON_MAX_LENGTH || decidedByMemberId <= 0
                || (previousFailureCode != null && (previousFailureCode.isBlank() || previousFailureCode.length() > FAILURE_CODE_MAX_LENGTH))) {
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

        /**
         * 이 유형에서 {@code status} 가 다음 시도를 기다리는 상태(환불 대기 · 복원 대기)인가 — 이때만 다음 시도 시각이 있다.
         * 환불 대기는 취소면 REQUESTED(접수 즉시 환불), 반품이면 APPROVED(검수 · 승인 뒤 환불)다. 복원 대기는 둘 다 REFUNDED 다.
         */
        public boolean isAwaitingAttempt(Status status) {
            Objects.requireNonNull(status, "status");
            return status == Status.REFUNDED || status == awaitingRefundStatus();
        }

        /** 이 유형의 환불 대기 상태 — 취소 REQUESTED(접수 즉시 환불), 반품 APPROVED(검수 · 승인 뒤 환불). */
        public Status awaitingRefundStatus() {
            return this == CANCELLATION ? Status.REQUESTED : Status.APPROVED;
        }

        /** 이 유형에서 다음 시도를 기다리는 상태 전부(환불 대기 · 복원 대기) — 재시도 스케줄러 질의의 상태 조건이다. */
        public Set<Status> awaitingAttemptStatuses() {
            return Collections.unmodifiableSet(EnumSet.of(awaitingRefundStatus(), Status.REFUNDED));
        }
    }

    /** 상태 — 이름(코드)이 DB · API 값이다. 선언 순서가 DB enum check 의 값 순서다(운영 DDL 과 같게 둔다). */
    @Getter
    @RequiredArgsConstructor
    public enum Status implements CodeNameDescribable {
        REQUESTED("접수", "클레임이 접수되었습니다. 환불은 아직 실행되지 않았습니다."),
        APPROVED("승인", "반품을 승인했습니다. 환불을 기다립니다."),
        REFUNDED("환불 완료", "환불이 확정되었습니다. 재고 복원을 기다립니다."),
        COMPLETED("완료", "환불과 재고 복원이 끝났습니다."),
        REJECTED("거절", "클레임이 거절되었습니다. 환불과 재고 복원이 없습니다."),
        REFUND_FAILED("환불 실패", "환불이 거절되었습니다. 운영자 확인이 필요합니다."),
        RESTORATION_FAILED("재고 복원 실패", "환불은 확정되었지만 재고 복원이 거절되었습니다. 운영자 확인이 필요합니다.");

        private final String displayName;
        private final String description;

        /** 종결 실패인가 — 이때만 실패 코드가 있다. */
        public boolean isFailed() {
            return this == REFUND_FAILED || this == RESTORATION_FAILED;
        }
    }
}
