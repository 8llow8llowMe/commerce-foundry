package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 결제 시도 1건 ({@code payment-guide.md} §5). 결제 시도 하나가 {@code payment} 행 하나, PG 주문번호({@link #pgOrderId}) 하나다 — 실패 뒤 재시도는 새 결제 시도다.
 * 상태는 아래 허용 전이 표의 도메인 메서드로만 바뀐다(setter 가 없다). 전이는 새 인스턴스를 돌려주고, 표 밖은 {@link PaymentTransitionException} 이다.
 *
 * <table>
 *   <caption>허용 전이 (표 밖은 {@link PaymentTransitionException} — Processor 가 결제 오류 코드로 바꾼다)</caption>
 *   <tr><th>현재</th><th>메서드</th><th>다음</th></tr>
 *   <tr><td>READY</td><td>{@link #startApproval}</td><td>IN_PROGRESS (되돌린 READY 면 같은 결제키만)</td></tr>
 *   <tr><td>READY (결제키 없음)</td><td>{@link #abort}</td><td>ABORTED</td></tr>
 *   <tr><td>READY (결제키 있음 — 되돌린 READY)</td><td>{@link #abortAfterInquiry}</td><td>ABORTED</td></tr>
 *   <tr><td>IN_PROGRESS</td><td>{@link #approve}</td><td>DONE</td></tr>
 *   <tr><td>IN_PROGRESS</td><td>{@link #reject}</td><td>FAILED</td></tr>
 *   <tr><td>IN_PROGRESS</td><td>{@link #markUnknown}</td><td>UNKNOWN</td></tr>
 *   <tr><td>IN_PROGRESS</td><td>{@link #revertToReady}</td><td>READY</td></tr>
 *   <tr><td>UNKNOWN</td><td>{@link #resolve}</td><td>DONE · FAILED</td></tr>
 *   <tr><td>UNKNOWN</td><td>{@link #flagForManualReview}</td><td>UNKNOWN (이유만 바뀐다)</td></tr>
 *   <tr><td>DONE</td><td>{@link #markOrderSynced} · {@link #markOrderSyncFailed}</td><td>DONE (주문 반영 기록만 바뀐다)</td></tr>
 *   <tr><td>FAILED · ABORTED</td><td>{@link #recordLateApproval}</td><td>LATE_APPROVED</td></tr>
 * </table>
 *
 * <p>같은 상태로의 재호출은 기록만 바꾸는 메서드({@link #markOrderSynced} · {@link #markOrderSyncFailed} · {@link #flagForManualReview})만 멱등이다 —
 * 같은 값이면 <b>같은 인스턴스</b>를 돌려주고, 호출자는 그때 저장하지 않는다(쓸데없는 버전 증가 · 충돌을 막는다). 승인 · 거절 같은 상태 전이의 재호출
 * (이미 DONE 인데 {@link #approve})은 예외다 — 중복 승인 경로를 조용히 통과시키지 않고 Processor 가 현재 상태를 보고 응답을 정하게 한다 ({@code payment-guide.md} §3-2).
 *
 * <p>승인을 시도한 적이 있는 시도(결제키가 있다)는 PG 에서 승인됐을 수 있다. 그래서 되돌린 READY 는 PG 조회로 미승인을 확인한 뒤에만 버리고({@link #abortAfterInquiry}),
 * 다시 승인할 때도 같은 결제키만 받는다 — 다른 키로 바꾸면 재조회가 이전 키의 승인을 돌려줄 때 기록과 영영 어긋난다. 종결(FAILED · ABORTED) 뒤 PG 가 승인을 알려 오면
 * {@link #recordLateApproval} 로 남긴다 — 돈이 빠졌으니 전액 취소 대상이다.
 *
 * <p>승인 결과의 금액 · 결제키가 어긋나면 {@link PaymentApprovalMismatchException} 이다. 만들 때 상태별 불변식을 지킨다 — 어긋나면 {@link IllegalArgumentException} 이다.
 * 같은 조건 중 행 하나로 볼 수 있는 것은 DB check 제약도 막는다({@code PaymentEntity}).
 *
 * @param id 결제 시도 아이디 (Snowflake)
 * @param orderId 주문 아이디 (order-service). 주문당 활성 결제 시도는 하나다 — {@link #activeOrderId()}
 * @param memberId 결제한 회원 아이디 (auth-service)
 * @param pgOrderId PG 에 보내는 주문번호 — 결제 시도 아이디 문자열이다. 승인 · 취소된 번호는 PG 가 재사용을 거절하므로 시도마다 새로 만든다
 * @param amount 결제 금액 — order-service 가 계산한 주문 금액이다. 프론트가 보낸 금액이 아니다 ({@code payment-guide.md} §3-1)
 * @param orderName PG 결제창에 보이는 주문명 (1~100자)
 * @param paymentKey PG 결제키. 승인 시작 전이면 null. 한 번 정해지면 바뀌지 않는다. 로그 · {@link #toString()} 에는 앞 8자만 남긴다
 * @param method 승인된 결제수단. 승인 전이면 null
 * @param easyPayProvider 간편결제 제공자. 승인 전이거나 간편결제가 아니면 null
 * @param pgStatus 승인 때 PG 가 준 원본 상태 문자열. 승인 전이면 null
 * @param approvalStartedAt 마지막으로 승인 요청을 시작한 시각 — IN_PROGRESS 로 남은 행을 복구할 때의 단서다 ({@code payment-guide.md} §3-3). 되돌린 뒤에도 흔적으로 남는다
 * @param approvedAt PG 승인 시각
 * @param failureCode PG 거절 코드 (FAILED, 그리고 FAILED 에서 온 LATE_APPROVED)
 * @param failureMessage PG 거절 메시지 (거절 코드가 있을 때만, 없을 수 있다)
 * @param unknownReason UNKNOWN 으로 둔 이유. 재조회로 확정한 뒤에도 이력으로 남긴다
 * @param orderSynced 결제 완료를 order-service 에 반영했는가 ({@code payment-guide.md} §6). 주문에 반영하는 상태(DONE 계열)만 true 가 된다
 * @param orderSyncFailureCode 주문 반영이 종결 실패했을 때 order-service 가 준 오류 코드. 반영 전 · 반영 성공이면 null
 * @param abortReason 중단 사유 (ABORTED, 그리고 ABORTED 에서 온 LATE_APPROVED)
 * @param version 낙관적 락 버전. 아직 저장하지 않은 결제는 null 이다
 */
public record Payment(
    long id, long orderId, long memberId, String pgOrderId, PaymentGateway gateway, Money amount, String orderName, PaymentStatus status,
    String paymentKey, PaymentMethod method, String easyPayProvider, String pgStatus, Instant approvalStartedAt, Instant approvedAt,
    String failureCode, String failureMessage, PaymentUnknownReason unknownReason, boolean orderSynced, String orderSyncFailureCode,
    PaymentAbortReason abortReason, Long version
) {

    /** 토스 orderId 규칙 — 6~64자, 영문 대소문자 · 숫자 · {@code -} · {@code _}. */
    public static final int PG_ORDER_ID_MIN_LENGTH = 6;
    public static final int PG_ORDER_ID_MAX_LENGTH = 64;
    /** 토스 orderName 상한. */
    public static final int ORDER_NAME_MAX_LENGTH = 100;
    /** 토스 paymentKey 상한. */
    public static final int PAYMENT_KEY_MAX_LENGTH = 200;
    public static final int EASY_PAY_PROVIDER_MAX_LENGTH = 50;
    public static final int PG_STATUS_MAX_LENGTH = 30;
    public static final int FAILURE_CODE_MAX_LENGTH = 100;
    /** 토스 failure.message 상한. */
    public static final int FAILURE_MESSAGE_MAX_LENGTH = 510;
    public static final int ORDER_SYNC_FAILURE_CODE_MAX_LENGTH = 30;

    private static final int MASKED_PAYMENT_KEY_PREFIX_LENGTH = 8;

    public Payment {
        Objects.requireNonNull(pgOrderId, "pgOrderId");
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(orderName, "orderName");
        Objects.requireNonNull(status, "status");
        requirePgOrderId(pgOrderId);
        if (amount.isZero()) {
            throw new IllegalArgumentException("결제 금액은 0 보다 커야 합니다");
        }
        if (orderName.isBlank()) {
            throw new IllegalArgumentException("주문명이 비었습니다");
        }
        requireMaxLength(orderName, ORDER_NAME_MAX_LENGTH, "orderName");
        if (paymentKey != null) {
            requirePaymentKey(paymentKey);
        }
        requireMaxLength(easyPayProvider, EASY_PAY_PROVIDER_MAX_LENGTH, "easyPayProvider");
        requireMaxLength(pgStatus, PG_STATUS_MAX_LENGTH, "pgStatus");
        requireMaxLength(failureCode, FAILURE_CODE_MAX_LENGTH, "failureCode");
        requireMaxLength(failureMessage, FAILURE_MESSAGE_MAX_LENGTH, "failureMessage");
        requireMaxLength(orderSyncFailureCode, ORDER_SYNC_FAILURE_CODE_MAX_LENGTH, "orderSyncFailureCode");
        requireStatusFields(status, paymentKey, method, easyPayProvider, pgStatus, approvalStartedAt, approvedAt,
            failureCode, failureMessage, unknownReason, orderSynced, orderSyncFailureCode, abortReason);
    }

    /**
     * 새 결제 시도(READY). PG 주문번호는 결제 시도 아이디 문자열이다 — Snowflake 아이디라 시도마다 다르고, 토스 규칙(숫자만 · 6자 이상)을 만족한다.
     *
     * @param amount 주문 금액. 지금은 KRW 만 받는다 — USD(PayPal)는 해외 결제 이슈(#20)에서 소수 변환 · 결제수단 제한과 함께 연다
     * @throws IllegalArgumentException 금액이 0 이하 · KRW 가 아님 · 주문명이 비었거나 100자 초과 · 아이디가 6자리 미만일 때
     */
    public static Payment ready(long id, long orderId, long memberId, Money amount, String orderName) {
        Objects.requireNonNull(amount, "amount");
        if (amount.currency() != CurrencyCode.KRW) {
            throw new IllegalArgumentException("지금은 KRW 결제만 받습니다: " + amount.currency());
        }
        return new Payment(
            id, orderId, memberId, Long.toString(id), PaymentGateway.TOSS, amount, orderName, PaymentStatus.READY,
            null, null, null, null, null, null, null, null, null, false, null, null, null
        );
    }

    /**
     * 주문당 활성 결제 시도를 하나로 막는 유니크 키 값. 실패(FAILED) · 버림(ABORTED) · 늦은 승인(LATE_APPROVED)이면 null 이라 같은 주문에 새 결제 시도를
     * 만들 수 있고, 그 밖은 주문 아이디다 — UNKNOWN 은 돈이 빠졌을 수 있어 슬롯을 놓지 않는다.
     * 취소 상태(PARTIAL_CANCELED · CANCELED)는 승인된 결제와 같이 활성으로 둔다 — 취소된 주문에 새 결제를 열지는 취소 이슈에서 정한다(지금은 닿지 않는 상태다).
     */
    public Long activeOrderId() {
        return status.releasesActiveSlot() ? null : orderId;
    }

    /**
     * READY → IN_PROGRESS. 승인 요청(PG 호출) <b>전에</b> 저장해 커밋한다 — 동시 confirm 중 하나만 버전 검사를 통과하고, PG 승인 뒤 기록이 실패해도
     * 이 기록이 복구의 단서가 된다 ({@code payment-guide.md} §3-2 · §3-3).
     *
     * <p>되돌린 READY(결제키가 이미 있다)는 같은 결제키로만 다시 시작한다 — 이전 키가 PG 에서 승인됐을 수 있어 다른 키로 바꾸지 않는다.
     *
     * @param paymentKey successUrl 로 돌아온 PG 결제키 (1~200자)
     * @throws IllegalArgumentException 결제키가 비었거나 200자를 넘을 때
     * @throws PaymentApprovalMismatchException 되돌린 READY 에 다른 결제키가 올 때 ({@link PaymentApprovalMismatchException.Field#PAYMENT_KEY})
     */
    public Payment startApproval(String paymentKey, Instant now) {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(now, "now");
        requireStatus(PaymentStatus.READY, PaymentTransitionException.Action.START_APPROVAL);
        requirePaymentKey(paymentKey);
        if (this.paymentKey != null && !this.paymentKey.equals(paymentKey)) {
            throw new PaymentApprovalMismatchException(id, PaymentApprovalMismatchException.Field.PAYMENT_KEY);
        }
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, PaymentStatus.IN_PROGRESS,
            paymentKey, null, null, null, now, null, null, null, null, false, null, null, version
        );
    }

    /**
     * READY → ABORTED. 승인을 시도한 적이 없는(결제키가 없는) READY 만 바로 버린다 — 주문의 활성 슬롯을 놓는다.
     * 되돌린 READY 는 이전 결제키가 PG 에서 승인됐을 수 있어 {@link #abortAfterInquiry} 로만 버린다(바로 버리면 새 시도와 이중 결제가 된다).
     */
    public Payment abort(PaymentAbortReason reason) {
        Objects.requireNonNull(reason, "reason");
        requireStatus(PaymentStatus.READY, PaymentTransitionException.Action.ABORT);
        if (paymentKey != null) {
            throw new PaymentTransitionException(status, PaymentTransitionException.Action.ABORT);
        }
        return aborted(reason);
    }

    /**
     * 되돌린 READY(결제키 있음) → ABORTED. PG 조회로 이 결제키가 승인되지 않았음을 확인했을 때만 버린다 — 인자가 그 확인이다.
     * 조회가 승인을 돌려주면 이 메서드가 아니라 다시 승인 흐름(같은 결제키로 {@link #startApproval})이다.
     *
     * @param notApproved PG 조회 결과 — 승인되지 않음. 이 결제 시도의 결제키에 대한 결과여야 한다 — 다른 결제키의 실패는 이 결제키가 미승인이라는 증거가 아니다
     * @throws PaymentTransitionException READY 가 아니거나, 결제키가 없거나, 조회 결과의 결제키가 이 결제 시도의 결제키와 다를 때
     */
    public Payment abortAfterInquiry(PaymentInquiry.Failed notApproved, PaymentAbortReason reason) {
        Objects.requireNonNull(notApproved, "notApproved");
        Objects.requireNonNull(reason, "reason");
        requireStatus(PaymentStatus.READY, PaymentTransitionException.Action.ABORT_AFTER_INQUIRY);
        if (paymentKey == null || !paymentKey.equals(notApproved.paymentKey())) {
            // 승인을 시도한 적이 없으면 조회할 결제키도 없다 — abort 를 쓴다. 다른 결제키의 실패로는 버리지 않는다
            throw new PaymentTransitionException(status, PaymentTransitionException.Action.ABORT_AFTER_INQUIRY);
        }
        return aborted(reason);
    }

    /**
     * IN_PROGRESS → DONE. 주문 반영 전이라 {@link #orderSynced()} 는 false 다.
     *
     * @throws PaymentApprovalMismatchException PG 결제키 · 승인 금액이 이 결제 시도와 다를 때 — 승인으로 기록하지 않는다.
     *     Processor 가 {@link #markUnknown}({@link PaymentUnknownReason#APPROVAL_MISMATCH}) 로 돌린다
     */
    public Payment approve(PaymentApproval approval) {
        Objects.requireNonNull(approval, "approval");
        requireStatus(PaymentStatus.IN_PROGRESS, PaymentTransitionException.Action.APPROVE);
        return approved(approval);
    }

    /**
     * IN_PROGRESS → FAILED. PG 가 명확히 거절했을 때(4xx — 한도 초과 · 카드 거절 등)만이다. 타임아웃 · 5xx 는 {@link #markUnknown} 이다 ({@code payment-guide.md} §4).
     * 주문의 활성 슬롯을 놓아 고객이 새 결제 시도로 다시 결제할 수 있다.
     *
     * @param failureMessage PG 오류 메시지. 510자를 넘으면 잘라 저장한다 — 표시용 문구라 잘려도 판단이 바뀌지 않고, 길이 때문에 실패 기록을 잃지 않게
     */
    public Payment reject(String failureCode, String failureMessage) {
        Objects.requireNonNull(failureCode, "failureCode");
        requireStatus(PaymentStatus.IN_PROGRESS, PaymentTransitionException.Action.REJECT);
        return failed(failureCode, failureMessage);
    }

    /** IN_PROGRESS → UNKNOWN. 승인 요청을 보냈는지 · 승인됐는지 모른다. 활성 슬롯을 놓지 않는다 — 재조회 스케줄러가 확정한다. */
    public Payment markUnknown(PaymentUnknownReason reason) {
        Objects.requireNonNull(reason, "reason");
        requireStatus(PaymentStatus.IN_PROGRESS, PaymentTransitionException.Action.MARK_UNKNOWN);
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, PaymentStatus.UNKNOWN,
            paymentKey, null, null, null, approvalStartedAt, null, null, null, reason, false, null, null, version
        );
    }

    /**
     * IN_PROGRESS → READY. 승인 요청을 <b>보내지 않은 것이 확실할 때만</b> 부른다 — 서킷 오픈으로 호출 전에 막혔거나 연결 자체가 맺어지지 않았을 때.
     * 보냈는지 모르면 {@link #markUnknown} 이다 ({@code payment-guide.md} §4). 결제키 · 승인 시작 시각은 흔적으로 남긴다 — 이 READY 는 같은 결제키로만 다시 시작하고
     * ({@link #startApproval}), 버릴 때는 PG 조회를 거친다({@link #abortAfterInquiry}).
     */
    public Payment revertToReady() {
        requireStatus(PaymentStatus.IN_PROGRESS, PaymentTransitionException.Action.REVERT_TO_READY);
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, PaymentStatus.READY,
            paymentKey, null, null, null, approvalStartedAt, null, null, null, null, false, null, null, version
        );
    }

    /**
     * UNKNOWN → DONE · FAILED. PG 조회 결과로 확정한다 — 승인됐으면 {@link #approve}, 승인되지 않았으면 {@link #reject} 와 같은 칸을 채운다.
     * UNKNOWN 으로 둔 이유({@link #unknownReason()})는 이력으로 남긴다.
     *
     * @throws PaymentApprovalMismatchException 승인 결과의 결제키 · 금액, 또는 실패 결과의 결제키가 이 결제 시도와 다를 때 — UNKNOWN 그대로 두고
     *     Processor 가 {@link #flagForManualReview} 로 사람 확인에 넘긴다. 다른 결제키의 실패로 FAILED 를 확정하면 이 결제키의 승인을 놓친다
     */
    public Payment resolve(PaymentInquiry inquiry) {
        Objects.requireNonNull(inquiry, "inquiry");
        requireStatus(PaymentStatus.UNKNOWN, PaymentTransitionException.Action.RESOLVE);
        return switch (inquiry) {
            case PaymentInquiry.Approved approvedInquiry -> approved(approvedInquiry.approval());
            case PaymentInquiry.Failed failedInquiry -> {
                if (!paymentKey.equals(failedInquiry.paymentKey())) {
                    throw new PaymentApprovalMismatchException(id, PaymentApprovalMismatchException.Field.PAYMENT_KEY);
                }
                yield failed(failedInquiry.failureCode(), failedInquiry.failureMessage());
            }
        };
    }

    /**
     * UNKNOWN → UNKNOWN. 미확정 이유만 바꾼다 — 재조회로 확정할 수 없는 시도(승인 불일치 등)를 사람 확인으로 넘기면 재조회 스케줄러가 건너뛰고 알림만 한다.
     * 같은 이유면 그대로 돌려준다(멱등).
     *
     * @param reason 보통 {@link PaymentUnknownReason#MANUAL_REVIEW} · {@link PaymentUnknownReason#APPROVAL_MISMATCH}
     */
    public Payment flagForManualReview(PaymentUnknownReason reason) {
        Objects.requireNonNull(reason, "reason");
        requireStatus(PaymentStatus.UNKNOWN, PaymentTransitionException.Action.FLAG_FOR_MANUAL_REVIEW);
        if (reason == unknownReason) {
            return this;
        }
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, status,
            paymentKey, null, null, null, approvalStartedAt, null, null, null, reason, false, null, null, version
        );
    }

    /**
     * 결제 완료를 order-service 에 반영했다 ({@code payment-guide.md} §6). 이전 종결 실패 코드는 지운다. 이미 반영됐으면 그대로 돌려준다(멱등 — 재전송이 겹친다).
     */
    public Payment markOrderSynced() {
        requireStatus(PaymentStatus.DONE, PaymentTransitionException.Action.MARK_ORDER_SYNCED);
        if (orderSynced) {
            return this;
        }
        return withOrderSync(true, null);
    }

    /**
     * 주문 반영이 종결 실패했다 — 재전송해도 바뀌지 않는 거절(예: 주문이 이미 취소됨). 반영 여부는 false 그대로라 재전송 · 자동 취소 대상으로 남는다.
     * 이미 반영됐으면 그대로 돌려준다 — 늦게 도착한 실패가 성공 기록을 덮지 않게. 같은 코드면 그대로다(멱등).
     *
     * @param failureCode order-service 오류 코드 (1~30자)
     */
    public Payment markOrderSyncFailed(String failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");
        requireStatus(PaymentStatus.DONE, PaymentTransitionException.Action.MARK_ORDER_SYNC_FAILED);
        if (failureCode.isBlank()) {
            throw new IllegalArgumentException("주문 반영 실패 코드가 비었습니다");
        }
        if (orderSynced || failureCode.equals(orderSyncFailureCode)) {
            return this;
        }
        return withOrderSync(false, failureCode);
    }

    /**
     * FAILED · ABORTED → LATE_APPROVED. 이미 종결한 시도를 PG 가 승인했다고 알려 왔다(웹훅 · 재조회 · 대사) — 돈이 빠졌으니 승인 칸을 남기고 전액 취소 대상으로 둔다.
     * 주문에 반영하지 않고 활성 슬롯도 다시 잡지 않는다 — 그 사이 같은 주문에 다른 시도가 생겼을 수 있다. 종결 이유(거절 코드 · 중단 사유)는 이력으로 남긴다.
     * 취소(→ CANCELED)는 취소 이슈에서 연다.
     *
     * @throws PaymentApprovalMismatchException 이 시도에 결제키가 있는데 다르거나 금액이 다를 때 — 다른 시도의 승인을 이 시도에 붙이지 않는다
     */
    public Payment recordLateApproval(PaymentApproval approval) {
        Objects.requireNonNull(approval, "approval");
        if (status != PaymentStatus.FAILED && status != PaymentStatus.ABORTED) {
            throw new PaymentTransitionException(status, PaymentTransitionException.Action.RECORD_LATE_APPROVAL);
        }
        // 결제키 없이 버린 시도는 PG 승인 요청(confirm)을 보낸 적이 없어 정상 경로로는 승인될 수 없다 — 그래도 PG 가 승인을 알려 오면 그 결제키로 남긴다
        if (paymentKey != null) {
            requireSamePaymentKey(approval);
        }
        requireSameAmount(approval);
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, PaymentStatus.LATE_APPROVED,
            approval.paymentKey(), approval.method(), approval.easyPayProvider(), approval.pgStatus(), approvalStartedAt, approval.approvedAt(),
            failureCode, failureMessage, unknownReason, false, null, abortReason, version
        );
    }

    /** 결제키 전체를 로그에 남기지 않는다 ({@code coding-conventions.md} §8) — 레코드 기본 {@code toString} 은 모든 칸을 그대로 찍는다. 거절 메시지도 찍지 않는다. */
    @Override
    public String toString() {
        return ("Payment[id=%d, orderId=%d, memberId=%d, pgOrderId=%s, gateway=%s, amount=%s, status=%s, paymentKey=%s, method=%s, approvedAt=%s,"
            + " failureCode=%s, unknownReason=%s, orderSynced=%s, orderSyncFailureCode=%s, abortReason=%s, version=%s]").formatted(
                id, orderId, memberId, pgOrderId, gateway, amount, status, maskPaymentKey(paymentKey), method, approvedAt,
                failureCode, unknownReason, orderSynced, orderSyncFailureCode, abortReason, version
            );
    }

    /** 앞 8자 + {@code ***}. 8자 이하면 전부 가린다. null 이면 null. */
    static String maskPaymentKey(String paymentKey) {
        if (paymentKey == null) {
            return null;
        }
        if (paymentKey.length() <= MASKED_PAYMENT_KEY_PREFIX_LENGTH) {
            return "***";
        }
        return paymentKey.substring(0, MASKED_PAYMENT_KEY_PREFIX_LENGTH) + "***";
    }

    private Payment approved(PaymentApproval approval) {
        requireSamePaymentKey(approval);
        requireSameAmount(approval);
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, PaymentStatus.DONE,
            paymentKey, approval.method(), approval.easyPayProvider(), approval.pgStatus(), approvalStartedAt, approval.approvedAt(),
            null, null, unknownReason, false, null, null, version
        );
    }

    private Payment failed(String code, String message) {
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, PaymentStatus.FAILED,
            paymentKey, null, null, null, approvalStartedAt, null, code, truncate(message, FAILURE_MESSAGE_MAX_LENGTH), unknownReason, false, null, null, version
        );
    }

    private Payment aborted(PaymentAbortReason reason) {
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, PaymentStatus.ABORTED,
            paymentKey, null, null, null, approvalStartedAt, null, null, null, null, false, null, reason, version
        );
    }

    private Payment withOrderSync(boolean synced, String syncFailureCode) {
        return new Payment(
            id, orderId, memberId, pgOrderId, gateway, amount, orderName, status,
            paymentKey, method, easyPayProvider, pgStatus, approvalStartedAt, approvedAt,
            failureCode, failureMessage, unknownReason, synced, syncFailureCode, abortReason, version
        );
    }

    /** 다른 결제 시도의 결제키로 승인 결과를 기록하면 돈과 기록이 어긋난다 — 어댑터 · Processor 가 결과를 잘못 짝지었거나 결제키가 변조됐다. */
    private void requireSamePaymentKey(PaymentApproval approval) {
        if (!approval.paymentKey().equals(paymentKey)) {
            throw new PaymentApprovalMismatchException(id, PaymentApprovalMismatchException.Field.PAYMENT_KEY);
        }
    }

    private void requireSameAmount(PaymentApproval approval) {
        if (!approval.amount().equals(amount)) {
            throw new PaymentApprovalMismatchException(id, PaymentApprovalMismatchException.Field.AMOUNT);
        }
    }

    private void requireStatus(PaymentStatus expected, PaymentTransitionException.Action action) {
        if (status != expected) {
            throw new PaymentTransitionException(status, action);
        }
    }

    /** 상태별로 있어야 하는 칸과 없어야 하는 칸. 전이 메서드가 지키고, 저장된 행을 읽을 때(매퍼)도 다시 본다. DB check 제약({@code PaymentEntity})과 같은 규칙이다. */
    private static void requireStatusFields(
        PaymentStatus status, String paymentKey, PaymentMethod method, String easyPayProvider, String pgStatus, Instant approvalStartedAt, Instant approvedAt,
        String failureCode, String failureMessage, PaymentUnknownReason unknownReason, boolean orderSynced, String orderSyncFailureCode,
        PaymentAbortReason abortReason
    ) {
        if (approvalStartedAt != null && paymentKey == null) {
            throw new IllegalArgumentException("승인 시작 시각이 있으면 결제키도 있어야 합니다: " + status);
        }
        if ((status == PaymentStatus.IN_PROGRESS || status == PaymentStatus.UNKNOWN) && (paymentKey == null || approvalStartedAt == null)) {
            throw new IllegalArgumentException("승인 요청 중인 결제에는 결제키 · 승인 시작 시각이 있어야 합니다: " + status);
        }
        if (status.hasApproval() && (paymentKey == null || method == null || approvedAt == null)) {
            throw new IllegalArgumentException("승인된 결제에는 결제키 · 결제수단 · 승인 시각이 있어야 합니다: " + status);
        }
        if (!status.hasApproval() && (method != null || approvedAt != null || easyPayProvider != null || pgStatus != null)) {
            throw new IllegalArgumentException("결제수단 · 승인 시각 · 간편결제 제공자 · PG 상태는 승인된 결제에만 있어야 합니다: " + status);
        }
        if (status == PaymentStatus.FAILED && failureCode == null) {
            throw new IllegalArgumentException("FAILED 결제에는 거절 코드가 있어야 합니다");
        }
        if (status != PaymentStatus.FAILED && status != PaymentStatus.LATE_APPROVED && failureCode != null) {
            throw new IllegalArgumentException("거절 코드는 FAILED · LATE_APPROVED 에만 있어야 합니다: " + status);
        }
        if (failureMessage != null && failureCode == null) {
            throw new IllegalArgumentException("거절 메시지는 거절 코드와 함께만 있어야 합니다: " + status);
        }
        if (status == PaymentStatus.UNKNOWN && unknownReason == null) {
            throw new IllegalArgumentException("UNKNOWN 결제에는 미확정 이유가 있어야 합니다");
        }
        if (neverUnknown(status) && unknownReason != null) {
            throw new IllegalArgumentException("UNKNOWN 을 거칠 수 없는 상태에는 미확정 이유가 없어야 합니다: " + status);
        }
        if (status == PaymentStatus.ABORTED && abortReason == null) {
            throw new IllegalArgumentException("ABORTED 결제에는 중단 사유가 있어야 합니다");
        }
        if (status != PaymentStatus.ABORTED && status != PaymentStatus.LATE_APPROVED && abortReason != null) {
            throw new IllegalArgumentException("중단 사유는 ABORTED · LATE_APPROVED 에만 있어야 합니다: " + status);
        }
        if (status == PaymentStatus.LATE_APPROVED && (failureCode == null) == (abortReason == null)) {
            throw new IllegalArgumentException("늦은 승인은 거절 코드 · 중단 사유 중 하나(종결 이유)만 가집니다");
        }
        if (orderSynced && !status.reportsToOrder()) {
            throw new IllegalArgumentException("주문 반영은 주문에 반영하는 상태(DONE 계열)만 기록합니다: " + status);
        }
        if (orderSyncFailureCode != null && (orderSynced || !status.reportsToOrder())) {
            throw new IllegalArgumentException("주문 반영 실패 코드는 DONE 계열이고 반영 전인 결제에만 있어야 합니다: " + status);
        }
    }

    /** UNKNOWN 을 거쳐 올 수 없는 상태 — 미확정 이유가 이력으로라도 있으면 안 된다. */
    private static boolean neverUnknown(PaymentStatus status) {
        return status == PaymentStatus.READY || status == PaymentStatus.IN_PROGRESS || status == PaymentStatus.ABORTED
            || status == PaymentStatus.WAITING_FOR_DEPOSIT;
    }

    /** 입력값은 메시지에 싣지 않는다 — 길이와 위반 종류만. */
    private static void requirePgOrderId(String value) {
        if (value.length() < PG_ORDER_ID_MIN_LENGTH || value.length() > PG_ORDER_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("PG 주문번호는 %d~%d자여야 합니다: %d자".formatted(PG_ORDER_ID_MIN_LENGTH, PG_ORDER_ID_MAX_LENGTH, value.length()));
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                || character == '-' || character == '_';
            if (!allowed) {
                throw new IllegalArgumentException("PG 주문번호는 영문 · 숫자 · '-' · '_' 만 쓸 수 있습니다: %d번째 문자".formatted(index + 1));
            }
        }
    }

    private static void requirePaymentKey(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("결제키가 비었습니다");
        }
        requireMaxLength(value, PAYMENT_KEY_MAX_LENGTH, "paymentKey");
    }

    /** DB {@code varchar(n)} 은 문자(코드 포인트) 수로 센다 — 같은 기준으로 본다. */
    private static void requireMaxLength(String value, int maxLength, String label) {
        if (value != null && value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("%s 는 %d자 이하여야 합니다".formatted(label, maxLength));
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
