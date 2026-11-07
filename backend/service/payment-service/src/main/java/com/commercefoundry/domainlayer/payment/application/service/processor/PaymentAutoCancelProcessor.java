package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAutoCancelStore;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAutoCancelReviewPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentAutoCancelClaimCollisionException;
import java.util.Objects;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short committed transactions; all writers lock payment before the intent or cancellation ledger. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
public class PaymentAutoCancelProcessor {
    private final PaymentAutoCancelStore intents;
    private final PaymentCancelPersistencePort cancellations;
    private final PaymentRepositoryPort payments;
    private final Clock clock;
    private final SnowflakeIdGenerator ids;
    private final PaymentAutoCancelReviewPort reviews;

    public void enqueue(long paymentId) {
        Payment payment = cancellations.lockPayment(paymentId).orElse(null);
        if (payment != null && eligible(payment) && intents.find(paymentId).isEmpty()) {
            intents.save(PaymentAutoCancel.intent(paymentId, clock.instant()));
        }
    }

    /** Returns only after the claim, amount and lease have committed through the Spring proxy. */
    public Attempt acquire(long paymentId) {
        Payment payment = cancellations.lockPayment(paymentId).orElse(null);
        PaymentAutoCancel intent = intents.find(paymentId).orElse(null);
        Instant now = clock.instant();
        if (payment == null || intent == null || !intent.due(now)) {
            return null;
        }
        if (!eligible(payment)) {
            manual(intent);
            return null;
        }
        List<PaymentCancel> all = cancellations.findByPaymentId(paymentId);
        PaymentCancel owned = all.stream().filter(c -> Objects.equals(c.claimId(), intent.claimId())).findFirst().orElse(null);
        if (intent.claimId() != null && (owned == null || owned.status() != PaymentCancel.Status.PENDING || !owned.amount().equals(intent.amount()))) {
            manual(intent);
            return null;
        }
        if (all.stream().anyMatch(c -> c.status() == PaymentCancel.Status.PENDING && !Objects.equals(c.claimId(), intent.claimId()))) {
            intents.save(intent.retry(now));
            return null;
        }
        Money succeeded = succeeded(payment, all);
        if (succeeded.isGreaterThan(payment.amount()) || succeeded.equals(payment.amount())) {
            manual(intent);
            return null;
        }
        Money remaining = payment.amount().minus(succeeded);
        PaymentCancel existing = all.stream().filter(c -> Objects.equals(c.claimId(), intent.claimId())).findFirst().orElse(null);
        if ((intent.amount() != null && !intent.amount().equals(remaining))
            || (intent.claimId() != null && (existing == null || existing.status() != PaymentCancel.Status.PENDING || !existing.amount().equals(remaining)))) {
            manual(intent);
            return null;
        }
        long claimId;
        if (intent.claimId() == null) {
            claimId = ids.generateId();
            if (cancellations.findByClaimId(claimId).isPresent()) throw new PaymentAutoCancelClaimCollisionException();
            cancellations.insertNewAutoClaim(new PaymentCancel(claimId, paymentId, remaining, PaymentCancel.Status.PENDING));
        } else {
            claimId = intent.claimId();
        }
        PaymentAutoCancel acquired = intents.save(intent.acquire(claimId, remaining, UUID.randomUUID().toString(), now));
        return new Attempt(payment, acquired);
    }

    public boolean recordEvidence(long paymentId, String token, String transactionKey) {
        cancellations.lockPayment(paymentId).orElseThrow();
        PaymentAutoCancel intent = intents.find(paymentId).orElseThrow();
        if (!intent.owns(token, clock.instant())) {
            return false;
        }
        if (intent.transactionKey() != null && !intent.transactionKey().equals(transactionKey)) {
            manual(intent);
            return false;
        }
        intents.save(intent.evidence(transactionKey));
        return true;
    }

    /** Stale workers cannot change either the intent, payment or ledger. */
    public boolean finish(long paymentId, String token, Outcome outcome) {
        Payment payment = cancellations.lockPayment(paymentId).orElseThrow();
        PaymentAutoCancel intent = intents.find(paymentId).orElseThrow();
        if (!intent.owns(token, clock.instant())) {
            return false;
        }
        if (outcome == Outcome.RETRY) {
            intents.save(intent.retry(clock.instant()));
            return true;
        }
        if (outcome == Outcome.MANUAL_REVIEW || !eligible(payment) || intent.transactionKey() == null) {
            manual(intent);
            return true;
        }
        List<PaymentCancel> all = cancellations.findByPaymentId(paymentId);
        PaymentCancel cancel = all.stream().filter(c -> Objects.equals(c.claimId(), intent.claimId())).findFirst().orElse(null);
        if (cancel == null) {
            manual(intent);
            return true;
        }
        Money cumulative = succeeded(payment, all).plus(cancel.amount());
        if (cancel.status() != PaymentCancel.Status.PENDING || !cancel.amount().equals(intent.amount()) || !cumulative.equals(payment.amount())
            || all.stream().anyMatch(c -> c.status() == PaymentCancel.Status.PENDING && !Objects.equals(c.claimId(), intent.claimId()))) {
            manual(intent);
            return true;
        }
        payments.save(payment.recordCancellation(cumulative));
        cancellations.save(cancel.succeeded());
        intents.save(intent.finish(true));
        return true;
    }

    private void manual(PaymentAutoCancel intent) {
        if (intent.state() != PaymentAutoCancel.State.PENDING) return;
        intents.save(intent.finish(false));
        try {
            reviews.afterCommit();
        } catch (RuntimeException ignored) {
            // A failed observation registration must not roll back the durable manual-review state.
        }
    }

    private static boolean eligible(Payment payment) {
        return payment.status() == PaymentStatus.LATE_APPROVED
            || ((payment.status() == PaymentStatus.DONE || payment.status() == PaymentStatus.PARTIAL_CANCELED)
                && !payment.orderSynced() && payment.orderSyncFailureCode() != null);
    }

    private static Money succeeded(Payment payment, List<PaymentCancel> all) {
        return all.stream().filter(c -> c.status() == PaymentCancel.Status.SUCCEEDED).map(PaymentCancel::amount)
            .reduce(Money.zero(payment.amount().currency()), Money::plus);
    }

    public record Attempt(Payment payment, PaymentAutoCancel intent) { }
    public enum Outcome { CONFIRMED, RETRY, MANUAL_REVIEW }
}
