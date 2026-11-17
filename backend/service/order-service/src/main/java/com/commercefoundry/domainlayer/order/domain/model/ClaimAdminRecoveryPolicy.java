package com.commercefoundry.domainlayer.order.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 결제 후 취소 · 승인된 반품 클레임의 운영자 수습 자격 — 다시 시도(#17 A2b) · 수동 환불 확정 · 복원 생략 종결(#17 A3, 반품 B2b)의 신규 실행 자격만 판정하는 순수 정책이다.
 * 상태 전이는 {@link Claim} · {@link Order} 의 도메인 메서드, 저장 · 버전 대조 · 원격 호출은 application 의 {@code ClaimAdminRecoveryProcessor} 가 한다.
 * 이미 처리된 요청의 멱등 성공 판정기가 아니다 — 처리된 뒤의 같은 요청은 화면 버전 불일치로 거절된다.
 *
 * <p>{@code is*Candidate} 는 주문을 읽지 않는 어드민 상세의 버튼 표시용이고, {@code require*} 는 주문까지 보는 실행 자격이다 — 둘은 같은 클레임 조건을 공유한다.
 */
public final class ClaimAdminRecoveryPolicy {
    private static final String ORDER_STATUS_UNEXPECTED = "ORDER_STATUS_UNEXPECTED";
    /**
     * 복원 생략 종결을 받는 확정 복원 실패 코드. {@code ORDER_STATUS_UNEXPECTED}(환불 때 주문이 취소 요청 · 취소 완료가 아니었다)는 넣지 않는다 —
     * 주문이 {@code CANCELED} 로 갈 경로가 없어 종결 자격(주문 취소 완료)을 영영 채우지 못한다. 그 건의 수습 경로는 #695 다(#17 A3).
     */
    private static final Set<String> COMPLETABLE_FAILURES = Set.of("PRODUCT_027", "PRODUCT_028", "PRODUCT_029", "PRODUCT_003", "RESTORATION_DATA_INVALID");

    private ClaimAdminRecoveryPolicy() {
    }

    public static void requireRetry(Claim claim, Instant now) {
        requireStored(claim);
        Objects.requireNonNull(now, "now");
        if ((!claim.isAwaitingAttempt() && !claim.status().isFailed()) || ORDER_STATUS_UNEXPECTED.equals(claim.failureCode())
            || (claim.nextAttemptAt() != null && claim.nextAttemptAt().isAfter(now))) {
            throw new IllegalStateException("활성 선점 · 종결 완료 · 주문 상태 이상 클레임은 재시도하지 않습니다");
        }
    }

    /**
     * 주문을 보지 않고 클레임만으로 본 수동 환불 확정 후보 (#17 A3, 반품 B2b) — 저장된 클레임의 {@code REFUND_FAILED} 중 결제사가 거절한 환불은 늘 후보다.
     * 결과 미확인({@link Claim#REFUND_UNCONFIRMED})과 반품의 막힘({@link Claim#isRefundWaiting} — 돈이 나가지 않았을 수 있다)은 다시 시도가 결제 취소 키
     * 기한으로 닫혔을 때만 후보다 — 기한 안이면 다시 시도가 정본 경로다.
     *
     * @param refundRetryClosed 이 클레임의 환불 다시 시도가 결제 취소 키 기한으로 닫혔는가 — 부르는 쪽이 다시 시도 판정과 같은 기준으로 계산한다
     */
    public static boolean isRefundConfirmationCandidate(Claim claim, boolean refundRetryClosed) {
        Objects.requireNonNull(claim, "claim");
        if (claim.isNew() || claim.status() != Claim.Status.REFUND_FAILED) {
            return false;
        }
        return claim.isRefundRejected() || refundRetryClosed;
    }

    /**
     * 수동 환불 확정의 실행 자격 (#17 A3, 반품 B2b) — {@link #isRefundConfirmationCandidate} 이고, 같은 저장 주문이 확정과 함께 정리될 수 있는 상태여야 한다.
     * 취소는 취소 요청(확정과 함께 취소 완료로 간다) 또는 이미 취소 완료, 반품은 반품 요청이다(확정과 함께 반품 정리 — {@link ReturnRefundPolicy}).
     * 그 밖의 주문 상태면 환불 확정을 기록해도 재고 복원 · 주문 전이를 이을 수 없다.
     */
    public static void requireRefundConfirmation(Claim claim, Order order, boolean refundRetryClosed) {
        requireStored(claim);
        requireSameStoredOrder(claim, order);
        boolean orderSettleable = claim.type() == Claim.Type.RETURN
            ? order.status() == OrderStatus.RETURN_REQUESTED
            : order.status() == OrderStatus.CANCEL_REQUESTED || order.status() == OrderStatus.CANCELED;
        if (!orderSettleable) {
            throw new IllegalStateException("확정과 함께 정리할 수 없는 주문 상태입니다: type=%s, status=%s".formatted(claim.type(), order.status()));
        }
        if (!isRefundConfirmationCandidate(claim, refundRetryClosed)) {
            throw new IllegalStateException("결제사 거절 또는 다시 시도 기한이 지난 결과 미확인 · 막힘 환불만 수동 확정합니다");
        }
    }

    /**
     * 복원 생략 종결의 실행 자격 (#17 A3, 반품 B2b) — 취소는 같은 주문이 취소 완료여야 한다. 반품은 주문 상태를 보지 않는다 — 반품 복원은 주문이 정리 뒤
     * 배송 완료 · 구매 확정으로 갔어도 진행하므로(B2a) 종결도 같다. 정산 → 정산 전이라 주문 잠금이 필요 없다({@code order-flow-guide.md} §5 B2a).
     */
    public static void requireCompletion(Claim claim, Order order) {
        requireStored(claim);
        requireSameStoredOrder(claim, order);
        if (claim.type() == Claim.Type.CANCELLATION && order.status() != OrderStatus.CANCELED) {
            throw new IllegalStateException("같은 주문의 취소 완료를 확인해야 종결합니다");
        }
        if (!isCompletionCandidate(claim)) {
            throw new IllegalStateException("확정된 복원 종결 실패만 수동 종결합니다");
        }
    }

    /** 주문을 보지 않고 클레임만으로 본 수동 종결 후보 — 어드민 상세의 버튼 표시용이다. 실행 자격은 주문까지 보는 {@link #requireCompletion} 이다 (#17 A3). */
    public static boolean isCompletionCandidate(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return !claim.isNew() && claim.status() == Claim.Status.RESTORATION_FAILED
            && COMPLETABLE_FAILURES.contains(claim.failureCode());
    }

    private static void requireSameStoredOrder(Claim claim, Order order) {
        Objects.requireNonNull(order, "order");
        if (order.isNew() || order.id() != claim.orderId()) {
            throw new IllegalStateException("같은 저장 주문이어야 수습합니다: orderId=" + order.id());
        }
    }

    /** 저장된 클레임만 수습한다 — 취소와 승인된 반품(#17 B2b). 승인 전 · 거절된 반품은 대기 · 실패 상태가 아니라 각 자격 조건에서 걸러진다. */
    private static void requireStored(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.isNew()) {
            throw new IllegalStateException("저장된 클레임만 수습합니다");
        }
    }
}
