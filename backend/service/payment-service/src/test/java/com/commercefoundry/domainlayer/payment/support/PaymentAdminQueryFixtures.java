package com.commercefoundry.domainlayer.payment.support;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;

public final class PaymentAdminQueryFixtures {
    public static final long ID = 4_611_686_018_427_387_904L;
    public static final Instant TIME = Instant.parse("2026-11-01T00:00:00Z");

    public static PaymentEntity entity(int offset, PaymentStatus status, boolean synced) {
        boolean started = status != PaymentStatus.READY;
        return PaymentEntity.builder().id(ID + offset).orderId(100L + offset).memberId(42L).pgOrderId(String.valueOf(ID + offset))
            .gateway(PaymentGateway.TOSS).totalAmount(129000L + offset).totalCurrency(CurrencyCode.KRW).orderName("상품")
            .status(status).activeOrderId(status.releasesActiveSlot() ? null : 100L + offset)
            .paymentKey(started ? "synthetic-payment-key-" + offset : null).approvalStartedAt(started ? TIME : null)
            .method(status.hasApproval() ? PaymentMethod.CARD : null).approvedAt(status.hasApproval() ? TIME : null)
            .unknownReason(status == PaymentStatus.READY ? null : PaymentUnknownReason.TIMEOUT)
            .orderSynced(synced).orderSyncFailureCode(status == PaymentStatus.DONE && !synced ? "ORDER_CANCELED" : null)
            .failureCode(status == PaymentStatus.FAILED ? "REJECT_CARD_COMPANY" : null)
            .failureMessage(status == PaymentStatus.FAILED ? "private diagnostic not for response" : null).build();
    }

    public static List<PaymentEntity> refundHistoryPayments() {
        return List.of(entity(1, PaymentStatus.DONE, false), entity(2, PaymentStatus.CANCELED, false),
            entity(3, PaymentStatus.DONE, false), entity(4, PaymentStatus.PARTIAL_CANCELED, false), entity(5, PaymentStatus.FAILED, false),
            entity(6, PaymentStatus.DONE, true));
    }

    public static List<PaymentCancelEntity> refundHistoryCancellations() {
        return List.of(cancel(11, 1, PaymentCancel.Status.REJECTED), cancel(12, 1, PaymentCancel.Status.REJECTED),
            cancel(21, 2, PaymentCancel.Status.REJECTED), cancel(22, 2, PaymentCancel.Status.SUCCEEDED),
            cancel(31, 3, PaymentCancel.Status.PENDING), cancel(41, 4, PaymentCancel.Status.SUCCEEDED),
            cancel(61, 6, PaymentCancel.Status.REJECTED),
            // #691 무환불 봉인(VOIDED)은 환불 실패 이력이 아니다 — 3 · 4 는 환불 실패 필터에 나오지 않아야 한다
            cancel(32, 3, PaymentCancel.Status.VOIDED), cancel(42, 4, PaymentCancel.Status.VOIDED));
    }

    /**
     * 외부 확인 환불(#693) 목록 시나리오. 결제 1~5 는 DONE · 3 만 PARTIAL_CANCELED, 6 은 원장 없음.
     * <ul>
     *   <li>1 — EXTERNAL_PG 300 + 200, OFF_PG 500 → pg 500 · offPg 500 (두 채널 · 여러 행)</li>
     *   <li>2 — OFF_PG 700 만 → pg 0 · offPg 700. 이전 REJECTED 가 외부 환불로 바뀐 건이라 환불 실패 필터에서 빠진다</li>
     *   <li>3 — EXTERNAL_PG 400 + SUCCEEDED 100 → pg 400 · offPg 0 (우리 PG 취소 성공은 외부 환불이 아니다)</li>
     *   <li>4 — REJECTED + OFF_PG 50 → 두 이력 필터에 모두 나온다</li>
     *   <li>5 — REJECTED · VOIDED · PENDING → 외부 환불 없음(null), 환불 실패 필터에만 나온다</li>
     * </ul>
     */
    public static List<PaymentEntity> externalRefundPayments() {
        return List.of(entity(1, PaymentStatus.DONE, false), entity(2, PaymentStatus.DONE, false), entity(3, PaymentStatus.PARTIAL_CANCELED, false),
            entity(4, PaymentStatus.DONE, false), entity(5, PaymentStatus.DONE, false), entity(6, PaymentStatus.DONE, true));
    }

    public static List<PaymentCancelEntity> externalRefundCancellations() {
        return List.of(cancel(11, 1, PaymentCancel.Status.EXTERNAL_PG_REFUNDED, 300), cancel(12, 1, PaymentCancel.Status.EXTERNAL_PG_REFUNDED, 200),
            cancel(13, 1, PaymentCancel.Status.OFF_PG_REFUNDED, 500), cancel(21, 2, PaymentCancel.Status.OFF_PG_REFUNDED, 700),
            cancel(31, 3, PaymentCancel.Status.EXTERNAL_PG_REFUNDED, 400), cancel(32, 3, PaymentCancel.Status.SUCCEEDED, 100),
            cancel(41, 4, PaymentCancel.Status.REJECTED, 100), cancel(42, 4, PaymentCancel.Status.OFF_PG_REFUNDED, 50),
            cancel(51, 5, PaymentCancel.Status.REJECTED, 100), cancel(52, 5, PaymentCancel.Status.VOIDED, 100),
            cancel(53, 5, PaymentCancel.Status.PENDING, 100));
    }

    private static PaymentCancelEntity cancel(long claimId, int offset, PaymentCancel.Status status) {
        long amount = offset == 2 && status == PaymentCancel.Status.SUCCEEDED ? 129000L + offset : 100;
        return cancel(claimId, offset, status, amount);
    }

    private static PaymentCancelEntity cancel(long claimId, int offset, PaymentCancel.Status status, long amount) {
        return PaymentCancelEntity.from(new PaymentCancel(claimId, ID + offset, Money.of(amount, CurrencyCode.KRW), status));
    }

    private PaymentAdminQueryFixtures() {
    }
}
