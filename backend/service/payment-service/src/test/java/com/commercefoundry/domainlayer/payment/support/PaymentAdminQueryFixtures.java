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
            cancel(61, 6, PaymentCancel.Status.REJECTED));
    }

    private static PaymentCancelEntity cancel(long claimId, int offset, PaymentCancel.Status status) {
        long amount = offset == 2 && status == PaymentCancel.Status.SUCCEEDED ? 129000L + offset : 100;
        return PaymentCancelEntity.from(new PaymentCancel(claimId, ID + offset, Money.of(amount, CurrencyCode.KRW), status));
    }

    private PaymentAdminQueryFixtures() {
    }
}
