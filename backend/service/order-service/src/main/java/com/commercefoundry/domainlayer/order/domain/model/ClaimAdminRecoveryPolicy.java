package com.commercefoundry.domainlayer.order.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 결제 후 취소 · 승인된 반품 클레임의 운영자 수습 자격 — 다시 시도(#17 A2b) · 수동 환불 확정 · 복원 생략 종결(#17 A3, 반품 B2b) · 취소 거절(#17 A4, 취소만)의
 * 신규 실행 자격만 판정하는 순수 정책이다.
 * 상태 전이는 {@link Claim} · {@link Order} 의 도메인 메서드, 저장 · 버전 대조 · 원격 호출은 application 의 {@code ClaimAdminRecoveryProcessor} 가 한다.
 * 이미 처리된 요청의 멱등 성공 판정기가 아니다 — 처리된 뒤의 같은 요청은 화면 버전 불일치로 거절된다.
 *
 * <p>{@code is*Candidate} 는 주문을 읽지 않는 어드민 상세의 버튼 표시용이고, {@code require*} 는 주문까지 보는 실행 자격이다 — 둘은 같은 클레임 조건을 공유한다.
 */
public final class ClaimAdminRecoveryPolicy {
    private static final String ORDER_STATUS_UNEXPECTED = "ORDER_STATUS_UNEXPECTED";
    /**
     * 복원 생략 종결을 받는 확정 복원 실패 코드. {@code ORDER_STATUS_UNEXPECTED}(환불 때 주문을 정리할 수 없었다 — 주문이 취소 요청 · 취소 완료가 아니었거나
     * 정리 판정이 불가능했다)는 넣지 않는다 — 주문이 정리될 경로가 없어 종결해도 주문이 맞지 않는다. 그 건의 수습 경로는 #695 다(#17 A3).
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
     * 취소는 취소 요청(확정과 함께 취소 정리 — 전량이면 취소 완료, 일부면 결제 완료로 복귀, #684 {@link CancellationRefundPolicy}) 또는 이미 취소 완료,
     * 반품은 반품 요청이다(확정과 함께 반품 정리 — {@link ReturnRefundPolicy}). 그 밖의 주문 상태면 환불 확정을 기록해도 재고 복원 · 주문 전이를 이을 수 없다.
     * 열린 취소는 주문당 하나이고 그동안 주문은 취소 요청이라, 결제 완료로 돌아간 주문의 환불 실패 취소는 데이터 이상이다.
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
     * 복원 생략 종결의 실행 자격 (#17 A3, 반품 B2b, 부분 취소 #684 PR-2) — 주문 전 항목 · 전 수량을 담은 취소(부분 취소 없던 전체 취소)는 같은 주문이 취소 완료여야
     * 한다 — 그 정리 결과는 늘 취소 완료다. 일부 수량만 담은 취소(부분 취소 · 부분 취소 뒤 남은 수량의 전체 취소)와 반품은 주문 상태를 보지 않는다 — 부분 취소
     * 정리 뒤 주문은 결제 완료로 돌아가 상품 준비중 · 배송 · 다음 취소로 넘어갈 수 있고, 복원은 주문 상태와 무관하게 진행하므로(B2a) 종결도 같다. 주문을 정리하지
     * 못한 클레임({@code ORDER_STATUS_UNEXPECTED})은 실패 코드에서 이미 빠진다. 정산 → 정산 전이라 주문 잠금이 필요 없다({@code order-flow-guide.md} §5 B2a).
     */
    public static void requireCompletion(Claim claim, Order order) {
        requireStored(claim);
        requireSameStoredOrder(claim, order);
        if (claim.type() == Claim.Type.CANCELLATION && coversWholeOrder(claim, order) && order.status() != OrderStatus.CANCELED) {
            throw new IllegalStateException("같은 주문의 취소 완료를 확인해야 종결합니다");
        }
        if (!isCompletionCandidate(claim)) {
            throw new IllegalStateException("확정된 복원 종결 실패만 수동 종결합니다");
        }
    }

    /** 클레임이 주문의 모든 항목을 주문 수량 그대로 담았는가 — 부분 취소 없이 한 전체 취소다(#684). */
    private static boolean coversWholeOrder(Claim claim, Order order) {
        if (claim.items().size() != order.itemIds().size()) {
            return false;
        }
        for (ClaimItem item : claim.items()) {
            int index = order.itemIds().indexOf(item.orderItemId());
            if (index < 0 || order.quote().lines().get(index).quantity() != item.quantity()) {
                return false;
            }
        }
        return true;
    }

    /** 주문을 보지 않고 클레임만으로 본 수동 종결 후보 — 어드민 상세의 버튼 표시용이다. 실행 자격은 주문까지 보는 {@link #requireCompletion} 이다 (#17 A3). */
    public static boolean isCompletionCandidate(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return !claim.isNew() && claim.status() == Claim.Status.RESTORATION_FAILED
            && COMPLETABLE_FAILURES.contains(claim.failureCode());
    }

    /**
     * 주문을 보지 않고 클레임만으로 본 취소 거절 후보 (#17 A4) — 어드민 상세의 버튼 표시용이다. 실행 자격은 주문 · 같은 주문의 클레임 · 이력 · 결제 기록까지 보는
     * {@link #requireCancellationRejection} 이다. 두 조건을 공유한다.
     *
     * <ul>
     *   <li>R1 — 저장된 취소 클레임이고 결제사가 종결 거절한 환불 실패다({@link Claim#isRefundRejected()}: REFUND_FAILED 이고 {@link Claim#REFUND_UNCONFIRMED} 아님)</li>
     *   <li>R2 — 다음 시도 시각이 없다(선점 · 대기 중인 클레임이 아니다)</li>
     * </ul>
     */
    public static boolean isCancellationRejectionCandidate(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return !claim.isNew() && claim.type() == Claim.Type.CANCELLATION && claim.isRefundRejected() && claim.nextAttemptAt() == null;
    }

    /**
     * 취소 거절의 실행 자격 (#17 A4) — 아래를 모두 만족해야 한다. 화면이 본 클레임 · 주문 버전 대조(R3)는 부르는 쪽(Processor)이 따로 한다.
     * 이 자격은 거절해도 된다는 증거가 아니다 — payment 의 무환불 판정(void)이 {@code NO_REFUND_CONFIRMED} 여야 한다({@code payment-guide.md} §8-1).
     *
     * <ul>
     *   <li>R1 · R2 — {@link #isCancellationRejectionCandidate}</li>
     *   <li>R4 — 같은 저장 주문이 취소 요청(CANCEL_REQUESTED)이다</li>
     *   <li>R5 — 같은 주문에 이 클레임 밖의 열린 취소({@link Claim#isOpenCancellation()})가 없다</li>
     *   <li>R6 — 주문 상태 이력의 최신 행이 결제 완료 · 제작 중 · 상품 준비중 → 취소 요청이다. 그 밖(이력 없음 · 다른 행)이면 되돌릴 상태를 확인하지 못해 거절하지 않는다</li>
     *   <li>R7 — 주문의 결제 완료 기록의 결제 아이디가 클레임의 결제 아이디와 같다</li>
     * </ul>
     *
     * @param orderClaims 같은 주문의 클레임 전부 — 이 클레임이 들어 있어도 된다(아이디로 뺀다). 다른 주문의 클레임이 섞이면 {@link IllegalArgumentException}
     * @param latestFromStatus 주문 상태 이력 최신 행의 이전 상태. 없으면 null
     * @param latestToStatus 주문 상태 이력 최신 행의 다음 상태. 이력을 읽지 못했으면 null
     * @param recordedPaymentId 주문의 결제 완료 기록({@link OrderPayment#paymentId()})의 결제 아이디
     * @return 주문을 되돌릴 상태(최신 이력 행의 이전 상태) — {@link Order#rejectCancellation} 에 넘긴다
     * @throws IllegalStateException 저장 전 클레임 · 같은 저장 주문이 아님 · R1 · R2 · R4~R7 위반
     * @throws IllegalArgumentException 다른 주문의 클레임이 목록에 섞임
     */
    public static OrderStatus requireCancellationRejection(
        Claim claim, Order order, List<Claim> orderClaims, OrderStatus latestFromStatus, OrderStatus latestToStatus, long recordedPaymentId
    ) {
        requireStored(claim);
        requireSameStoredOrder(claim, order);
        Objects.requireNonNull(orderClaims, "orderClaims");
        if (!isCancellationRejectionCandidate(claim)) {
            throw new IllegalStateException("결제사가 거절했고 진행 중이 아닌 취소 환불 실패만 취소 거절합니다: type=%s, status=%s".formatted(claim.type(), claim.status()));
        }
        if (order.status() != OrderStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("취소 요청 주문만 취소 거절합니다: status=" + order.status());
        }
        for (Claim other : orderClaims) {
            Objects.requireNonNull(other, "orderClaims element");
            if (other.orderId() != claim.orderId()) {
                throw new IllegalArgumentException("같은 주문의 클레임만 받습니다: orderId=%d, claimId=%d".formatted(claim.orderId(), other.id()));
            }
            if (other.id() != claim.id() && other.isOpenCancellation()) {
                throw new IllegalStateException("같은 주문에 열린 다른 취소가 있습니다: claimId=" + other.id());
            }
        }
        if (latestToStatus != OrderStatus.CANCEL_REQUESTED || latestFromStatus == null || !Order.isPaidBeforeShipment(latestFromStatus)) {
            throw new IllegalStateException("되돌릴 주문 상태를 이력에서 확인하지 못했습니다: %s -> %s".formatted(latestFromStatus, latestToStatus));
        }
        if (!Objects.equals(claim.paymentId(), recordedPaymentId)) {
            throw new IllegalStateException("주문의 결제 기록과 클레임의 결제가 다릅니다: claimId=" + claim.id());
        }
        return latestFromStatus;
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
