package com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_auto_cancel", indexes = {
    @Index(name = "idx_payment_auto_cancel_state_next_attempt_at_payment_id", columnList = "state,next_attempt_at,payment_id"),
    @Index(name = "idx_payment_auto_cancel_state_lease_until_payment_id", columnList = "state,lease_until,payment_id")
},
    uniqueConstraints = @UniqueConstraint(name = "uk_payment_auto_cancel_claim_id", columnNames = "claim_id"), check = {
        @CheckConstraint(name = "ck_payment_auto_cancel_amount",
            constraint = "(amount is null and currency is null) or (amount is not null and amount > 0 and currency is not null)"),
        @CheckConstraint(name = "ck_payment_auto_cancel_claim",
            constraint = "(claim_id is null and amount is null) or (claim_id is not null and claim_id > 0 and amount is not null)"),
        @CheckConstraint(name = "ck_payment_auto_cancel_attempts", constraint = "attempts >= 0"),
        @CheckConstraint(name = "ck_payment_auto_cancel_lease",
            constraint = "(lease_until is null and lease_token is null) or (lease_until is not null and lease_token is not null)")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAutoCancelEntity extends BaseEntity {
    @Id
    @Column(name = "payment_id", comment = "Payment ID reference to payment.id (no database FK constraint)")
    private Long paymentId;
    @Column(name = "claim_id")
    private Long claimId;
    @Column(name = "pg_key", nullable = false, length = 64)
    private String pgKey;
    @Column(name = "amount")
    private Long amount;
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3)
    private CurrencyCode currency;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private PaymentAutoCancel.State state;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "lease_token", length = 36)
    private String leaseToken;
    @Column(name = "first_attempt_at")
    private Instant firstAttemptAt;
    @Column(name = "transaction_key", length = 64)
    private String transactionKey;
    @Column(name = "manual_reason", length = 40)
    private String manualReason;
    @Version
    private Long version;

    public static PaymentAutoCancelEntity from(PaymentAutoCancel intent) {
        var entity = new PaymentAutoCancelEntity();
        entity.paymentId = intent.paymentId();
        entity.pgKey = intent.pgKey();
        entity.apply(intent);
        return entity;
    }

    public void apply(PaymentAutoCancel intent) {
        if (!paymentId.equals(intent.paymentId()) || !pgKey.equals(intent.pgKey())
            || (claimId != null && !claimId.equals(intent.claimId()))
            || (amount != null && (intent.amount() == null || amount != intent.amount().amount() || currency != intent.amount().currency()))) {
            throw new IllegalArgumentException("Frozen cancellation request changed");
        }
        claimId = intent.claimId();
        amount = intent.amount() == null ? null : intent.amount().amount();
        currency = intent.amount() == null ? null : intent.amount().currency();
        state = intent.state();
        attempts = intent.attempts();
        nextAttemptAt = intent.nextAttemptAt();
        leaseUntil = intent.leaseUntil();
        leaseToken = intent.leaseToken();
        firstAttemptAt = intent.firstAttemptAt();
        transactionKey = intent.transactionKey();
        manualReason = intent.manualReason();
    }

    public PaymentAutoCancel toDomain() {
        return new PaymentAutoCancel(paymentId, claimId, pgKey, amount == null ? null : Money.of(amount, currency), state, attempts,
            nextAttemptAt, leaseUntil, leaseToken, firstAttemptAt, transactionKey, manualReason);
    }
}
