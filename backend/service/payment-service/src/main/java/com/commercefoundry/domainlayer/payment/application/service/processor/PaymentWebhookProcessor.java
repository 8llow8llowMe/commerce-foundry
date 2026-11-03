package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookConflictException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentWebhookWork;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentWebhookInboxPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.Reason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.State;
import com.commercefoundry.global.properties.PaymentWebhookProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 건별 짧은 DB 트랜잭션. PG 호출은 이 컴포넌트 밖에서 수행한다. */
@Component
@RequiredArgsConstructor
public class PaymentWebhookProcessor {
    private final PaymentWebhookInboxPort inbox;
    private final PaymentRepositoryPort payments;
    private final PaymentProcessor paymentProcessor;
    private final PaymentWebhookProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Long> scan() {
        return inbox.findDue(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), properties.batchSize()).stream().map(PaymentWebhookInbox::id).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentWebhookWork> claim(long id) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var row = inbox.findById(id).filter(candidate -> candidate.due(now));
        if (row.isEmpty()) { return Optional.empty(); }
        var claimed = inbox.claim(row.get(), row.get().claim(UUID.randomUUID().toString(), now, properties.lease()), now);
        var payment = payments.findById(claimed.paymentId()).orElse(null);
        if (payment == null || payment.paymentKey() == null) {
            finish(claimed, State.MANUAL_REVIEW, Reason.PAYMENT_MISSING, now);
            return Optional.empty();
        }
        if (exhausted(claimed, now, false)) {
            finish(claimed, State.MANUAL_REVIEW, Reason.EXHAUSTED, now);
            return Optional.empty();
        }
        return Optional.of(new PaymentWebhookWork(claimed, payment));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(PaymentWebhookWork work, PgInquiryQueryResult result) {
        var owned = requireOwned(work.inbox());
        Payment current = payments.findById(work.payment().id()).orElse(null);
        if (!work.payment().equals(current)) {
            retry(owned, Reason.SNAPSHOT_CHANGED);
            return;
        }
        try {
            if (result instanceof PgInquiryQueryResult.Approved approved) {
                var approval = approved.approval();
                if (!current.paymentKey().equals(approval.paymentKey()) || !current.amount().equals(approval.amount())) {
                    finish(owned, State.MANUAL_REVIEW, Reason.PG_CONTRACT, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                    return;
                }
                if (current.status() == PaymentStatus.DONE && (!java.util.Objects.equals(current.approvedAt(), approval.approvedAt())
                    || current.method() != approval.method() || !java.util.Objects.equals(current.easyPayProvider(), approval.easyPayProvider())
                    || !java.util.Objects.equals(current.pgStatus(), approval.pgStatus()))) {
                    finish(owned, State.MANUAL_REVIEW, Reason.PG_CONTRACT, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                    return;
                }
                Payment next = switch (current.status()) {
                    case IN_PROGRESS -> current.approve(approval);
                    case UNKNOWN -> current.resolve(approved.toInquiry());
                    case READY -> current.recoverReturnedReady(PaymentUnknownReason.ALREADY_PROCESSED).resolve(approved.toInquiry());
                    case FAILED, ABORTED -> current.recordLateApproval(approval);
                    case DONE -> current;
                    default -> null;
                };
                if (next == null) { finish(owned, State.MANUAL_REVIEW, Reason.UNSUPPORTED_STATE, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)); return; }
                if (next != current) {
                    if (next.status() == PaymentStatus.DONE) { paymentProcessor.saveApproved(current, next); }
                    else { payments.save(next); }
                }
                finish(owned, next.status() == PaymentStatus.LATE_APPROVED ? State.MANUAL_REVIEW : State.COMPLETED,
                    next.status() == PaymentStatus.LATE_APPROVED ? Reason.LATE_APPROVAL : Reason.NONE, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                return;
            }
            if (result instanceof PgInquiryQueryResult.Failed failed) {
                if (!current.paymentKey().equals(failed.paymentKey())) {
                    finish(owned, State.MANUAL_REVIEW, Reason.PG_CONTRACT, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)); return;
                }
                Payment next = switch (current.status()) {
                    case IN_PROGRESS -> current.markUnknown(PaymentUnknownReason.ALREADY_PROCESSED).resolve(failed.toInquiry());
                    case UNKNOWN -> current.resolve(failed.toInquiry());
                    case READY -> current.abortAfterInquiry(failed.toInquiry(), PaymentAbortReason.RECOVERY_NOT_APPROVED);
                    default -> null;
                };
                if (next == null) { finish(owned, State.MANUAL_REVIEW, Reason.UNSUPPORTED_STATE, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)); return; }
                payments.save(next);
                finish(owned, State.COMPLETED, Reason.NONE, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                return;
            }
            if (result instanceof PgInquiryQueryResult.Pending pending && !current.paymentKey().equals(pending.paymentKey())
                || result instanceof PgInquiryQueryResult.Unknown unknown
                    && unknown.reason() != PaymentUnknownReason.TIMEOUT && unknown.reason() != PaymentUnknownReason.SERVER_ERROR) {
                finish(owned, State.MANUAL_REVIEW, Reason.PG_CONTRACT, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)); return;
            }
            retry(owned, Reason.PG_RETRY);
        } catch (PaymentApprovalMismatchException | PaymentTransitionException mismatch) {
            finish(owned, State.MANUAL_REVIEW, Reason.PG_CONTRACT, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
    }

    private PaymentWebhookInbox requireOwned(PaymentWebhookInbox expected) {
        var current = inbox.findById(expected.id()).orElseThrow(PaymentWebhookConflictException::new);
        if (!current.equals(expected) || !current.owns(expected.claimToken(), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
            throw new PaymentWebhookConflictException();
        }
        return current;
    }
    private void retry(PaymentWebhookInbox owned, Reason reason) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (exhausted(owned, now, true)) { finish(owned, State.MANUAL_REVIEW, Reason.EXHAUSTED, now); }
        else { finish(owned, State.RETRY, reason, now.plus(properties.backoff(owned.attempts()))); }
    }
    private boolean exhausted(PaymentWebhookInbox row, Instant now, boolean afterAttempt) {
        return (afterAttempt ? row.attempts() >= properties.maxAttempts() : row.attempts() > properties.maxAttempts())
            || !row.receivedAt().plus(properties.maxAge()).isAfter(now);
    }
    private void finish(PaymentWebhookInbox owned, State state, Reason reason, Instant availableAt) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // 반드시 마지막 DB 쓰기로 CAS. 만료·회수·버전 충돌은 결제와 outbox 변경까지 같은 트랜잭션으로 롤백한다.
        inbox.finish(owned, owned.finish(state, reason, availableAt, now), now);
    }
}
