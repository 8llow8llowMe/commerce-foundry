package com.commercefoundry.domainlayer.payment.domain.model;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentAutoCancelTest {
    private final Instant now = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void smallIdsUseValidFrozenKeyAndPersistPositiveClaim() {
        var intent = PaymentAutoCancel.intent(1, now);
        assertThat(intent.pgKey()).matches("[A-Za-z0-9_=-]{6,64}");
        assertThat(intent.claimId()).isNull();
        var leased = intent.acquire(42, krw(20), "a", now);
        var retry = leased.retry(now).acquire(42, krw(20), "b", now.plusSeconds(120));
        assertThat(retry.pgKey()).isEqualTo(intent.pgKey());
        assertThat(retry.amount()).isEqualTo(krw(20));
        assertThat(retry.claimId()).isEqualTo(42L);
        assertThatThrownBy(() -> retry.retry(now).acquire(43, krw(20), "c", now.plusSeconds(300))).isInstanceOf(IllegalStateException.class);
        assertThat(retry.firstAttemptAt()).isEqualTo(now);
        assertThat(retry.attempts()).isEqualTo(2);
        assertThat(retry.owns("a", now.plusSeconds(121))).isFalse();
        assertThatThrownBy(() -> retry.retry(now).acquire(42, krw(21), "c", now.plusSeconds(300))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiryAllowsInquiryLeaseButTerminalStateCannotAcquire() {
        var leased = PaymentAutoCancel.intent(1, now).acquire(42, krw(20), "a", now);
        assertThatThrownBy(() -> leased.acquire(42, krw(20), "b", now.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        var expired = leased.acquire(42, krw(20), "b", now.plusSeconds(15 * 86400));
        assertThat(expired.keyExpired(now.plusSeconds(15 * 86400))).isTrue();
        assertThat(expired.pgKey()).isEqualTo(leased.pgKey());
        assertThatThrownBy(() -> leased.finish(false).acquire(42, krw(20), "b", now.plusSeconds(300))).isInstanceOf(IllegalStateException.class);
        assertThat(leased.finish(false).manualReason()).isEqualTo(PaymentAutoCancel.MANUAL_REASON);
        assertThatThrownBy(() -> leased.finish(true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> leased.finish(false).finish(true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> leased.evidence("a").evidence("b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lateApprovalFullCancelKeepsInactiveSlotAndRejectsPartial() {
        var late = lateApproved();
        assertThatThrownBy(() -> late.recordCancellation(krw(1))).isInstanceOf(IllegalArgumentException.class);
        var canceled = late.recordCancellation(late.amount());
        assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.activeOrderId()).isNull();
        assertThat(canceled.failureCode()).isEqualTo(late.failureCode());
        assertThat(canceled.approvedAt()).isEqualTo(late.approvedAt());
        assertThatThrownBy(() -> new PaymentCancel(-42, PAYMENT_ID, krw(1), PaymentCancel.Status.PENDING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentCancel(-PAYMENT_ID, PAYMENT_ID, krw(1), PaymentCancel.Status.PENDING)).isInstanceOf(IllegalArgumentException.class);
    }
}
