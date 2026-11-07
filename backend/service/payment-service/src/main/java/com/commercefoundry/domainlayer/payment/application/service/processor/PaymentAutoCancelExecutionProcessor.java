package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.model.PaymentCancellationInfo;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor.Outcome;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import java.time.Clock;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentAutoCancelClaimCollisionException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit single-payment execution boundary. A separate scheduler will supply durable candidates. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class PaymentAutoCancelExecutionProcessor {
    private final PaymentAutoCancelProcessor processor;
    private final PaymentGatewayProcessor gateway;
    private final Clock clock;
    @Value("${payment.auto-cancel.enabled:false}")
    private boolean enabled;

    public void execute(long paymentId) {
        if (!enabled) {
            return;
        }
        PaymentAutoCancelProcessor.Attempt attempt = acquire(paymentId);
        if (attempt == null) {
            return;
        }
        PaymentAutoCancel intent = attempt.intent();
        String token = intent.leaseToken();
        var inquiry = gateway.inquireCancellation(attempt.payment(), intent.amount(), intent.pgKey(), intent.transactionKey());
        if (inquiry.state() == PaymentCancellationInfo.State.CONFIRMED) {
            if (intent.transactionKey() != null || (inquiry.transactionKey() != null
                && processor.recordEvidence(paymentId, token, inquiry.transactionKey()))) {
                processor.finish(paymentId, token, Outcome.CONFIRMED);
            } else if (inquiry.transactionKey() == null) {
                processor.finish(paymentId, token, Outcome.MANUAL_REVIEW);
            }
            return;
        }
        if (intent.keyExpired(clock.instant())) {
            processor.finish(paymentId, token, Outcome.MANUAL_REVIEW);
            return;
        }
        if (inquiry.state() == PaymentCancellationInfo.State.MANUAL_REVIEW || inquiry.state() == PaymentCancellationInfo.State.UNATTRIBUTED) {
            processor.finish(paymentId, token, Outcome.MANUAL_REVIEW);
            return;
        }
        if (inquiry.state() != PaymentCancellationInfo.State.OPEN) {
            processor.finish(paymentId, token, Outcome.RETRY);
            return;
        }
        if (intent.keyExpired(clock.instant()) || !intent.owns(token, clock.instant())) {
            processor.finish(paymentId, token, Outcome.MANUAL_REVIEW);
            return;
        }
        // Only verified unchanged pre-balance permits a POST with the same persisted key.
        var post = gateway.cancelAutomatic(attempt.payment(), intent.pgKey(), intent.amount());
        if (post.state() == PaymentCancellationInfo.State.MANUAL_REVIEW) {
            processor.finish(paymentId, token, Outcome.MANUAL_REVIEW);
            return;
        }
        if (post.state() != PaymentCancellationInfo.State.EVIDENCE || post.transactionKey() == null) {
            processor.finish(paymentId, token, intent.keyExpired(clock.instant()) ? Outcome.MANUAL_REVIEW : Outcome.RETRY);
            return;
        }
        if (!processor.recordEvidence(paymentId, token, post.transactionKey())) {
            return;
        }
        var confirmed = gateway.inquireCancellation(attempt.payment(), intent.amount(), intent.pgKey(), post.transactionKey());
        Outcome outcome = switch (confirmed.state()) {
            case CONFIRMED -> Outcome.CONFIRMED;
            case MANUAL_REVIEW, UNATTRIBUTED -> Outcome.MANUAL_REVIEW;
            default -> intent.keyExpired(clock.instant()) ? Outcome.MANUAL_REVIEW : Outcome.RETRY;
        };
        processor.finish(paymentId, token, outcome);
    }
    private PaymentAutoCancelProcessor.Attempt acquire(long paymentId) {
        for (int collision = 0; collision < 3; collision++) {
            try {
                return processor.acquire(paymentId); // Proxy starts a fresh transaction for each attempt.
            } catch (PaymentAutoCancelClaimCollisionException ignored) {
                // No claim/metadata was committed and no PG request has been sent.
            }
        }
        return null; // Durable intent remains due for a later scheduler cycle.
    }
}
