package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 결제 행 잠금으로 한 시점에 한 클레임만 PG 취소를 진행하게 한다. PG 호출은 이 컴포넌트 밖에서 수행한다. */
@Component
@RequiredArgsConstructor
public class PaymentCancelProcessor {

    private final PaymentCancelPersistencePort cancelPort;
    private final PaymentRepositoryPort paymentPort;
    private final Clock clock;

    @Transactional
    public Prepared prepare(long paymentId, long claimId, Money amount) {
        Payment payment = cancelPort.lockPayment(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
        if (amount.isZero() || amount.currency() != payment.amount().currency()) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        }
        List<PaymentCancel> all = cancelPort.findByPaymentId(paymentId);
        PaymentCancel existing = cancelPort.findByClaimId(claimId).orElse(null);
        if (existing != null && (existing.paymentId() != paymentId || !existing.amount().equals(amount))) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        if (existing == null && payment.status() != PaymentStatus.DONE && payment.status() != PaymentStatus.PARTIAL_CANCELED
            && payment.status() != PaymentStatus.LATE_APPROVED) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        if (all.stream().anyMatch(c -> c.status() == PaymentCancel.Status.PENDING && c.claimId() != claimId)) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        Money succeeded = all.stream().filter(c -> c.status() == PaymentCancel.Status.SUCCEEDED)
            .map(PaymentCancel::amount).reduce(Money.zero(payment.amount().currency()), Money::plus);
        if (existing != null) {
            if (existing.status() == PaymentCancel.Status.REJECTED) {
                throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
            }
            if (existing.status() == PaymentCancel.Status.PENDING && existing.createdAt() != null
                && existing.createdAt().isBefore(Instant.now(clock).minus(Duration.ofDays(14)))) {
                // PG 멱등 키는 첫 요청 후 15일만 유효하다. 이후 같은 키 재전송은 이중 취소가 될 수 있다.
                throw new PaymentException(PaymentErrorCode.CANCELLATION_UNCONFIRMED);
            }
            return new Prepared(payment, existing, existing.status() == PaymentCancel.Status.SUCCEEDED ? succeeded : succeeded.plus(amount));
        }
        Money cumulative = succeeded.plus(amount);
        if (cumulative.isGreaterThan(payment.amount())) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        }
        if (payment.status() == PaymentStatus.LATE_APPROVED && !cumulative.equals(payment.amount())) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        }
        PaymentCancel created = cancelPort.save(new PaymentCancel(claimId, paymentId, amount, PaymentCancel.Status.PENDING));
        return new Prepared(payment, created, cumulative);
    }

    @Transactional
    public Payment complete(long paymentId, long claimId) {
        Payment payment = cancelPort.lockPayment(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
        PaymentCancel cancel = cancelPort.findByClaimId(claimId).orElseThrow(() -> new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT));
        if (cancel.paymentId() != paymentId) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        if (cancel.status() == PaymentCancel.Status.SUCCEEDED) {
            return payment;
        }
        if (cancel.status() != PaymentCancel.Status.PENDING) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        Money cumulative = cancelPort.findByPaymentId(paymentId).stream()
            .filter(c -> c.status() == PaymentCancel.Status.SUCCEEDED)
            .map(PaymentCancel::amount).reduce(Money.zero(payment.amount().currency()), Money::plus).plus(cancel.amount());
        Payment changed = paymentPort.save(payment.recordCancellation(cumulative));
        cancelPort.save(cancel.succeeded());
        return changed;
    }

    @Transactional
    public void reject(long paymentId, long claimId) {
        cancelPort.lockPayment(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
        PaymentCancel cancel = cancelPort.findByClaimId(claimId).orElseThrow(() -> new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT));
        if (cancel.paymentId() != paymentId) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        if (cancel.status() == PaymentCancel.Status.PENDING) {
            cancelPort.save(cancel.rejected());
        }
    }

    public record Prepared(Payment payment, PaymentCancel cancel, Money cumulative) { }
}
