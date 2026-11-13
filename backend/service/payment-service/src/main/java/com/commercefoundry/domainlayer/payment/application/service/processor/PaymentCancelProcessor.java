package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancelLedger;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 결제 행 잠금으로 한 시점에 한 클레임만 PG 취소를 진행하게 한다. PG 호출은 이 컴포넌트 밖에서 수행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelProcessor {

    /** 확정(complete)이 외부 확인 환불 행을 만났다는 이상 신호 코드 (#693, {@code payment-guide.md} §8). 로그 검색 · 알림 규칙이 이 값을 찾는다. */
    public static final String EXTERNAL_REFUND_COMPLETION_CONFLICT = "PAYMENT_CANCEL_EXTERNAL_REFUND_CONFLICT";

    private final PaymentCancelPersistencePort cancelPort;
    private final PaymentRepositoryPort paymentPort;
    private final Clock clock;

    @Transactional
    public Prepared prepare(long paymentId, long claimId, Money amount) {
        if (claimId <= 0) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        Payment payment = cancelPort.lockPayment(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
        if (cancelPort.isAutoClaim(claimId)) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        if (amount.isZero() || amount.currency() != payment.amount().currency()) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        }
        List<PaymentCancel> all = cancelPort.findByPaymentId(paymentId);
        PaymentCancel existing = cancelPort.findByClaimId(claimId).orElse(null);
        if (existing != null && (existing.paymentId() != paymentId || !existing.amount().equals(amount))) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        PaymentCancelLedger ledger = PaymentCancelLedger.of(all, payment.amount().currency());
        if (existing != null && existing.status().countsAsRefunded()) {
            // SUCCEEDED · EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED — 이 클레임의 환불은 이미 확정됐다. PG 를 다시 부르지 않고 200 으로 답한다(#693 이중 환불 차단).
            // 다른 클레임의 PENDING 보다 먼저 본다 — 확정된 환불의 재요청을 016(종결 거절)로 돌려 주문을 REFUND_FAILED 로 만들지 않게.
            return new Prepared(payment, existing, ledger.pgCanceled());
        }
        if (existing == null && payment.status() != PaymentStatus.DONE && payment.status() != PaymentStatus.PARTIAL_CANCELED
            && payment.status() != PaymentStatus.LATE_APPROVED) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        if (all.stream().anyMatch(c -> c.status() == PaymentCancel.Status.PENDING && c.claimId() != claimId)) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        Money pgCumulative = ledger.pgCanceled().plus(amount);
        if (existing != null) {
            if (existing.status() != PaymentCancel.Status.PENDING) {
                // REJECTED · VOIDED(refusesRefund) 와 모르는 상태. VOIDED 는 무환불 봉인이다(#691) — 주문이 거절 · 재출고를 확정했을 수 있어 이 클레임으로는 PG 를 부르지 않는다.
                throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
            }
            if (existing.createdAt() != null && existing.createdAt().isBefore(Instant.now(clock).minus(PaymentCancel.PG_RETRY_WINDOW))) {
                // PG 멱등 키는 첫 요청 후 15일만 유효하다. 이후 같은 키 재전송은 이중 취소가 될 수 있다.
                throw new PaymentException(PaymentErrorCode.CANCELLATION_UNCONFIRMED);
            }
            return new Prepared(payment, existing, pgCumulative);
        }
        // 한도는 PG 밖 환불(OFF_PG)까지 포함한 환불 합계로 본다 — PG 잔액이 남아 있어도 고객에게 이미 돌려준 돈을 다시 보내지 않는다.
        if (ledger.refundedTotal().plus(amount).isGreaterThan(payment.amount())) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        }
        if (payment.status() == PaymentStatus.LATE_APPROVED && !pgCumulative.equals(payment.amount())) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        }
        PaymentCancel created = cancelPort.save(new PaymentCancel(claimId, paymentId, amount, PaymentCancel.Status.PENDING));
        return new Prepared(payment, created, pgCumulative);
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
        if (cancel.status().refundedExternally()) {
            // 우리 PG 취소가 성공했는데 이 claimId 가 이미 외부 확인 환불로 기록돼 있다(#693). 기록 명령은 PG 재전송 기간 + 여유가 지난 PENDING 만 바꾸므로
            // 정상 흐름에서는 없다 — PG 에서 두 번 빠졌을 수 있어 운영자가 PG 거래와 대조한다. 응답은 기존대로 016 이고, 금액 · 사유는 남기지 않는다.
            log.warn("payment cancel succeeded at PG but claim is already externally refunded. code={}, paymentId={}, claimId={}, status={}",
                EXTERNAL_REFUND_COMPLETION_CONFLICT, paymentId, claimId, cancel.status());
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        if (cancel.status() != PaymentCancel.Status.PENDING) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        // 결제 상태는 PG 상태의 거울이다 — PG 취소 누계(pgCanceled)로 전이하고, PG 밖 환불(OFF_PG)은 넣지 않는다.
        Money cumulative = PaymentCancelLedger.of(cancelPort.findByPaymentId(paymentId), payment.amount().currency()).pgCanceled().plus(cancel.amount());
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

    /**
     * @param cumulative 이 취소가 성공하면 될 PG 취소 누계(pgCanceled + 이 금액) — PG 취소 요청에 싣는다.
     *     이 클레임의 환불이 이미 확정됐으면({@link PaymentCancel.Status#countsAsRefunded}) 지금의 pgCanceled 이고 PG 를 부르지 않는다
     */
    public record Prepared(Payment payment, PaymentCancel cancel, Money cumulative) { }
}
