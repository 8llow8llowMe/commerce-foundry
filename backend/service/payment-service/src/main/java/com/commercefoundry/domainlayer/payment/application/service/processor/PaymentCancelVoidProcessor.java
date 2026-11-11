package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelVoidInfo;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelVoidInfo.Reason;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor.RetentionOutcome;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.properties.PaymentCancelVoidProperties;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 클레임의 무환불 판정 (#691, {@code payment-guide.md} §8-1). 두 짧은 트랜잭션으로 나눈다 — 둘 다 결제 행을 잠근다
 * ({@link PaymentCancelProcessor#prepare} 와 같은 잠금이라 같은 결제의 준비 · 확정 · 거절 · 다른 void 와 줄을 선다).
 *
 * <ol>
 *   <li>{@link #inspect} — 로컬 원장만으로 끝나는 판정(환불됨 · 대기 · 유예 · 다른 대기 · 잔액 부족 · 미지원 상태)을 내거나, PG 잔액 대조가 필요한 후보 스냅숏을 만든다</li>
 *   <li>(트랜잭션 밖) PG 잔액 대조 — Facade 몫. {@code MATCHED} 가 아니면 {@link Candidate#unconfirmedBy} 로 답한다</li>
 *   <li>{@link #seal} — 스냅숏이 그대로인지 다시 보고, 그대로일 때만 봉인한다(행이 없으면 VOIDED 엄격 INSERT)</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class PaymentCancelVoidProcessor {

    /** PG 잔액이 원장으로 설명되는 결제 상태. LATE_APPROVED · 승인 전 · 가상계좌 대기는 판정하지 않는다. */
    private static final Set<PaymentStatus> SUPPORTED = EnumSet.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED, PaymentStatus.CANCELED);

    private final PaymentCancelPersistencePort cancelPort;
    private final PaymentCancelVoidProperties properties;
    private final Clock clock;

    /**
     * 검증 순서는 결제 후 취소 준비({@link PaymentCancelProcessor#prepare})와 같다 — 결제 → 자동 취소 claimId → 금액 · 통화 → 기존 행.
     *
     * @param orderId 클레임이 속한 주문. 결제의 주문과 다르면 다른 주문의 결제를 가리킨 것이다
     * @throws PaymentException 404 {@code PAYMENT_001} 결제 없음 · 409 {@code PAYMENT_019} 결제의 주문이 다름 · 자동 취소 claimId ·
     *     409 {@code PAYMENT_017} 금액 0 · 통화가 결제와 다름 · 409 {@code PAYMENT_019} 기존 행과 결제 · 금액 · 통화가 다름
     */
    @Transactional
    public Inspection inspect(long paymentId, long orderId, long claimId, Money amount) {
        Payment payment = cancelPort.lockPayment(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
        if (payment.orderId() != orderId) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        if (cancelPort.isAutoClaim(claimId)) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        if (amount.isZero() || amount.currency() != payment.amount().currency()) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        }
        PaymentCancel existing = cancelPort.findByClaimId(claimId).orElse(null);
        if (existing != null && !sameClaim(existing, paymentId, amount)) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        List<PaymentCancel> all = cancelPort.findByPaymentId(paymentId);
        Money succeeded = succeeded(all, payment);
        Money retained = payment.amount().minus(succeeded);
        boolean sealed = sealed(existing);
        if (!SUPPORTED.contains(payment.status())) {
            return unconfirmed(payment, claimId, Reason.PAYMENT_STATE_UNSUPPORTED, retained, sealed);
        }
        Reason reason = classify(existing, Instant.now(clock), properties.rejectionSettleGrace());
        if (reason == Reason.CLAIM_REFUND_SUCCEEDED) {
            return new Decided(PaymentCancelVoidInfo.refunded(paymentId, claimId, payment.status(), retained));
        }
        if (!isCandidate(reason)) {
            return unconfirmed(payment, claimId, reason, retained, sealed);
        }
        if (hasOtherPending(all, claimId)) {
            return unconfirmed(payment, claimId, Reason.OTHER_CANCEL_PENDING, retained, sealed);
        }
        if (retained.amount() < amount.amount()) {
            return unconfirmed(payment, claimId, Reason.RETAINED_SHORT, retained, sealed);
        }
        return new Candidate(payment, claimId, amount, existing == null ? null : existing.status(), succeeded, reason);
    }

    /**
     * PG 가 {@code MATCHED} 로 답한 뒤에만 부른다. 스냅숏(이 claimId 행 상태 · SUCCEEDED 합계 · 결제 상태 · 다른 PENDING 없음 · 남은 금액 ≥ 환불액)이
     * 그대로면 봉인하고 무환불 확정을 답한다. 하나라도 바뀌었으면 쓰지 않고 {@code LEDGER_CHANGED} 다 — 확인 없이 봉인만 남는 상태를 만들지 않는다.
     * 스냅숏이 "행 없음"이었는데 지금 같은 결제 · 금액 · 통화의 VOIDED 면 동시 void 가 먼저 봉인한 것이다 — 쓰지 않고 같은 판정을 답한다.
     *
     * @throws PaymentException 409 {@code PAYMENT_012} — VOIDED INSERT 가 같은 claimId 의 다른 결제 행과 부딪쳤다
     */
    @Transactional
    public PaymentCancelVoidInfo seal(Candidate candidate) {
        long paymentId = candidate.payment().id();
        Payment payment = cancelPort.lockPayment(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
        PaymentCancel existing = cancelPort.findByClaimId(candidate.claimId()).orElse(null);
        List<PaymentCancel> all = cancelPort.findByPaymentId(paymentId);
        Money succeeded = succeeded(all, payment);
        Money retained = payment.amount().minus(succeeded);
        boolean voidedMeanwhile = candidate.claimStatus() == null && existing != null && existing.status() == PaymentCancel.Status.VOIDED;
        boolean claimUnchanged = Objects.equals(existing == null ? null : existing.status(), candidate.claimStatus()) || voidedMeanwhile;
        boolean unchanged = claimUnchanged
            && (existing == null || sameClaim(existing, paymentId, candidate.amount()))
            && succeeded.equals(candidate.succeeded())
            && payment.status() == candidate.payment().status()
            && !hasOtherPending(all, candidate.claimId())
            && retained.amount() >= candidate.amount().amount();
        if (!unchanged) {
            return PaymentCancelVoidInfo.unconfirmed(paymentId, candidate.claimId(), Reason.LEDGER_CHANGED, payment.status(), retained, sealed(existing));
        }
        if (existing == null) {
            cancelPort.insertVoided(PaymentCancel.voided(candidate.claimId(), paymentId, candidate.amount()));
        }
        return PaymentCancelVoidInfo.confirmed(paymentId, candidate.claimId(), candidate.reason(), payment.status(), retained);
    }

    /**
     * 이 claimId 행만 보고 판정 이유를 정한다. 후보({@code CLAIM_NEVER_REQUESTED} · {@code PG_REJECTED})가 될 수 있는 것은 행 없음 · VOIDED · 유예가 지난 REJECTED 뿐이다.
     * 모르는 상태는 닫힌 쪽(미확정)으로 답한다 — 수동 확정 상태(#693)가 생겨도 그 규칙을 정하기 전에는 거절을 열지 않는다.
     */
    static Reason classify(PaymentCancel existing, Instant now, Duration rejectionSettleGrace) {
        if (existing == null) {
            return Reason.CLAIM_NEVER_REQUESTED;
        }
        return switch (existing.status()) {
            case VOIDED -> Reason.CLAIM_NEVER_REQUESTED;
            case REJECTED -> existing.updatedAt() != null && !now.isBefore(existing.updatedAt().plus(rejectionSettleGrace))
                ? Reason.PG_REJECTED : Reason.REJECTION_SETTLING;
            case SUCCEEDED -> Reason.CLAIM_REFUND_SUCCEEDED;
            case PENDING -> Reason.CLAIM_REFUND_PENDING;
            default -> Reason.CLAIM_STATE_UNSUPPORTED;
        };
    }

    private static boolean isCandidate(Reason reason) {
        return reason == Reason.CLAIM_NEVER_REQUESTED || reason == Reason.PG_REJECTED;
    }

    private static boolean sameClaim(PaymentCancel existing, long paymentId, Money amount) {
        return existing.paymentId() == paymentId && existing.amount().equals(amount);
    }

    /** 응답의 {@code sealed} — 이 claimId 가 결제 후 취소 준비에서 영구 거절되는 행(VOIDED · REJECTED)이 있는가. */
    private static boolean sealed(PaymentCancel existing) {
        return existing != null && existing.status().refusesRefund();
    }

    private static boolean hasOtherPending(List<PaymentCancel> all, long claimId) {
        return all.stream().anyMatch(c -> c.status() == PaymentCancel.Status.PENDING && c.claimId() != claimId);
    }

    /** VOIDED · REJECTED · PENDING 은 합계에 넣지 않는다. */
    private static Money succeeded(List<PaymentCancel> all, Payment payment) {
        return all.stream().filter(c -> c.status() == PaymentCancel.Status.SUCCEEDED)
            .map(PaymentCancel::amount).reduce(Money.zero(payment.amount().currency()), Money::plus);
    }

    private static Decided unconfirmed(Payment payment, long claimId, Reason reason, Money retained, boolean sealed) {
        return new Decided(PaymentCancelVoidInfo.unconfirmed(payment.id(), claimId, reason, payment.status(), retained, sealed));
    }

    /** {@link #inspect} 의 결과 — 이미 정해진 판정이거나, PG 잔액 대조가 필요한 후보다. */
    public sealed interface Inspection permits Decided, Candidate { }

    public record Decided(PaymentCancelVoidInfo info) implements Inspection { }

    /**
     * tx1 스냅숏. {@code claimStatus} 가 null 이면 이 claimId 행이 없었다.
     *
     * @param succeeded 이 결제의 SUCCEEDED 취소 합계 — PG 기대 잔액은 결제액에서 이 값을 뺀 것이다
     * @param reason 봉인될 때 답할 이유({@code CLAIM_NEVER_REQUESTED} 또는 {@code PG_REJECTED})
     */
    public record Candidate(Payment payment, long claimId, Money amount, PaymentCancel.Status claimStatus, Money succeeded, Reason reason)
        implements Inspection {

        public Money expectedBalance() {
            return payment.amount().minus(succeeded);
        }

        /** PG 가 {@code MATCHED} 가 아니라고 답했을 때의 판정 — 봉인하지 않는다. {@code sealed} 는 tx1 에 있던 행 그대로다. */
        public PaymentCancelVoidInfo unconfirmedBy(RetentionOutcome outcome) {
            Reason why = switch (outcome) {
                case MISMATCHED -> Reason.PG_LEDGER_MISMATCH;
                case UNKNOWN -> Reason.PG_UNAVAILABLE;
                case UNSUPPORTED -> Reason.PG_CHECK_UNSUPPORTED;
                case MATCHED -> throw new IllegalArgumentException("MATCHED 는 봉인 재확인(seal)으로 간다");
            };
            boolean sealed = claimStatus != null && claimStatus.refusesRefund();
            return PaymentCancelVoidInfo.unconfirmed(payment.id(), claimId, why, payment.status(), expectedBalance(), sealed);
        }
    }
}
