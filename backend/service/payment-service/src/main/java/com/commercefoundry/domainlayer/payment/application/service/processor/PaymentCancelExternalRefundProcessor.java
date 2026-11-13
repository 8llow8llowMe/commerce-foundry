package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Reason;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor.Retention;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancelLedger;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancelLedger.Evidence;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.properties.PaymentCancelExternalRefundProperties;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 확인 환불 기록 (#693, {@code payment-guide.md} §8 "외부 확인 환불 기록 명령"). 무환불 판정({@link PaymentCancelVoidProcessor})과 같은 골격이다 —
 * 두 짧은 트랜잭션 모두 결제 행을 잠근다({@link PaymentCancelProcessor#prepare} 와 같은 잠금이라 같은 결제의 준비 · 확정 · 거절 · void 와 줄을 선다).
 * 잠금 없이 기록하면 동시에 들어온 준비가 한도를 넘겨 초과 환불할 수 있다.
 *
 * <ol>
 *   <li>{@link #inspect} — 로컬 원장만으로 끝나는 판정(이미 기록 · 이미 환불 · 미지원 상태 · 대기 · 유예 · 봉인 · 다른 미정착 취소 · 잔액 부족)을 내거나 PG 검증 후보를 만든다</li>
 *   <li>(트랜잭션 밖) PG 잔액 조회 — Facade 몫. {@link Candidate#resolve} 가 관측 잔액으로 기록 여부를 정한다</li>
 *   <li>{@link #record} — 스냅숏이 그대로인지 다시 보고, 그대로일 때만 기록한다(행 없음 = 엄격 INSERT, 있으면 전이 표대로 UPDATE)</li>
 * </ol>
 *
 * <p>상태 전이 가능 여부는 도메인 표({@link PaymentCancel.Status#canBecome}), 미설명 환불액 산술은 {@link PaymentCancelLedger#externalRefundEvidence} 가 정한다.
 * 이 Processor 에는 시간 규칙(PENDING 경과 · REJECTED 유예)과 스냅숏 재확인만 둔다. 로그에는 식별자 · 채널 · 판정만 남긴다 — 금액 · 사유는 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelExternalRefundProcessor {

    /** PG 채널에서 원장에 없는 환불액이 신고 금액보다 컸다는 이상 신호 코드 — claim 별 귀속을 확인하지 못했다(거래키 저장 후속). */
    public static final String EXTERNAL_REFUND_SURPLUS = "PAYMENT_EXTERNAL_REFUND_PG_SURPLUS";

    /** PG 잔액이 원장으로 설명되고 결제 상태 전이(recordCancellation)가 되는 상태. CANCELED 는 남은 금액이 없고, LATE_APPROVED 는 전액 자동 취소 대상이다. */
    private static final Set<PaymentStatus> SUPPORTED = EnumSet.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);

    private final PaymentCancelPersistencePort cancelPort;
    private final PaymentRepositoryPort paymentPort;
    private final PaymentCancelVoidProperties voidProperties;
    private final PaymentCancelExternalRefundProperties properties;
    private final Clock clock;

    /**
     * 오류 순서는 결제 후 취소 준비 · 무환불 판정과 같다 — 결제 → 주문 → 자동 취소 claimId → 금액 · 통화 → 기존 행.
     * 판정 순서는 이미 확정된 환불(멱등) → 결제 상태 → 이 claimId 행 상태 → 다른 미정착 취소 → 남은 금액이다. 멱등 판정을 결제 상태보다 앞에 두는 것은
     * PG 채널 전액 기록 뒤 결제가 CANCELED 가 돼도 같은 요청이 같은 답(RECORDED)을 받게 하려는 것이다.
     * 잠금 대기가 길어지면 끊는다(5초 — 자동 취소 스캔 · 회신 처리와 같은 수준).
     *
     * @throws PaymentException 404 {@code PAYMENT_001} 결제 없음 · 409 {@code PAYMENT_019} 결제의 주문이 다름 · 자동 취소 claimId ·
     *     409 {@code PAYMENT_017} 금액 0 · 통화가 결제와 다름 · 409 {@code PAYMENT_019} 기존 행과 결제 · 금액 · 통화가 다름
     */
    @Transactional(timeout = 5)
    public Inspection inspect(long paymentId, long orderId, long claimId, Money amount, Channel channel) {
        Objects.requireNonNull(channel, "channel");
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
        PaymentCancelLedger ledger = PaymentCancelLedger.of(all, payment.amount().currency());
        PaymentCancelExternalRefundInfo settled = alreadySettled(payment, existing, ledger);
        if (settled != null) {
            return new Decided(settled);
        }
        if (!SUPPORTED.contains(payment.status())) {
            return unconfirmed(payment, claimId, Reason.PAYMENT_STATE_UNSUPPORTED, ledger);
        }
        Instant now = Instant.now(clock);
        Reason refusal = refusal(existing, channel, now);
        if (refusal != null) {
            return unconfirmed(payment, claimId, refusal, ledger);
        }
        if (hasOtherUnsettled(all, claimId, now)) {
            return unconfirmed(payment, claimId, Reason.OTHER_CANCEL_PENDING, ledger);
        }
        if (amount.isGreaterThan(ledger.retained(payment.amount()))) {
            return unconfirmed(payment, claimId, Reason.RETAINED_SHORT, ledger);
        }
        return new Candidate(payment, claimId, amount, channel, existing == null ? null : existing.status(), ledger);
    }

    /**
     * PG 관측 잔액이 신고 채널을 설명할 때만 부른다({@link Candidate#resolve} 가 {@link Verified} 를 낸 경우). 스냅숏(이 claimId 행 상태 · 두 합계 · 결제 상태 ·
     * 다른 미정착 취소 없음 · 남은 금액 ≥ 환불액)이 그대로면 기록하고, PG 채널이면 결제 상태를 {@code recordCancellation(pgCanceled + 금액)} 으로 옮긴다.
     * PG 밖 채널은 결제 상태를 바꾸지 않는다. 하나라도 바뀌었으면 쓰지 않고 {@code LEDGER_CHANGED} 다.
     * 그 사이 이 claimId 의 환불이 확정됐으면(같은 요청의 기록 · 우리 PG 취소 성공) 쓰지 않고 그 상태로 답한다.
     *
     * @throws PaymentException 409 {@code PAYMENT_012} — INSERT 가 같은 claimId 의 다른 결제 행과 부딪쳤거나 UPDATE 가 낙관 락에서 졌다
     */
    @Transactional(timeout = 5)
    public PaymentCancelExternalRefundInfo record(Verified verified) {
        Candidate candidate = verified.candidate();
        long paymentId = candidate.payment().id();
        Payment payment = cancelPort.lockPayment(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
        PaymentCancel existing = cancelPort.findByClaimId(candidate.claimId()).orElse(null);
        List<PaymentCancel> all = cancelPort.findByPaymentId(paymentId);
        PaymentCancelLedger ledger = PaymentCancelLedger.of(all, payment.amount().currency());
        if (existing != null && sameClaim(existing, paymentId, candidate.amount())) {
            PaymentCancelExternalRefundInfo settled = alreadySettled(payment, existing, ledger);
            if (settled != null) {
                return settled;
            }
        }
        boolean unchanged = Objects.equals(existing == null ? null : existing.status(), candidate.claimStatus())
            && (existing == null || sameClaim(existing, paymentId, candidate.amount()))
            && ledger.equals(candidate.ledger())
            && payment.status() == candidate.payment().status()
            && !hasOtherUnsettled(all, candidate.claimId(), Instant.now(clock))
            && !candidate.amount().isGreaterThan(ledger.retained(payment.amount()));
        if (!unchanged) {
            log.info("external refund not recorded; ledger changed during PG inquiry. paymentId={}, claimId={}", paymentId, candidate.claimId());
            return unconfirmedInfo(payment, candidate.claimId(), Reason.LEDGER_CHANGED, ledger);
        }
        PaymentCancel.Status target = candidate.channel().status();
        PaymentCancel row = existing == null
            ? PaymentCancel.externallyRefunded(candidate.claimId(), paymentId, candidate.amount(), target)
            : existing.externallyRefunded(target);
        cancelPort.recordExternalRefund(row);
        Payment after = payment;
        if (target.reducesPgBalance()) {
            // 결제 상태는 PG 상태의 거울이다 — PG 에서 빠진 금액(pgCanceled)으로만 전이한다. PG 밖 환불은 결제 상태에 넣지 않는다.
            after = paymentPort.save(payment.recordCancellation(ledger.pgCanceled().plus(candidate.amount())));
        }
        Money refunded = ledger.refundedTotal().plus(candidate.amount());
        log.info("external refund recorded. paymentId={}, claimId={}, channel={}, previousClaimStatus={}, paymentStatus={}",
            paymentId, candidate.claimId(), candidate.channel(), candidate.claimStatus(), after.status());
        if (verified.evidence() == Evidence.SURPLUS) {
            // PG 에서 원장 밖으로 빠진 돈이 이 신고보다 많다 — 다른 클레임의 콘솔 환불이 섞였을 수 있다. 어느 클레임 몫인지는 거래키 저장(후속 이슈) 전에는 가리지 못한다.
            log.warn("external refund recorded with unexplained PG refund beyond this claim. code={}, paymentId={}, claimId={}",
                EXTERNAL_REFUND_SURPLUS, paymentId, candidate.claimId());
        }
        return PaymentCancelExternalRefundInfo.recorded(paymentId, candidate.claimId(), Reason.PG_VERIFIED, candidate.channel(), after.status(),
            refunded, payment.amount().minus(refunded));
    }

    /** 이 claimId 의 환불이 이미 확정됐으면 그 답(외부 기록 = RECORDED · 기록된 채널, SUCCEEDED = ALREADY_REFUNDED), 아니면 null. PG 를 부르지 않는다. */
    private static PaymentCancelExternalRefundInfo alreadySettled(Payment payment, PaymentCancel existing, PaymentCancelLedger ledger) {
        if (existing == null) {
            return null;
        }
        Money refunded = ledger.refundedTotal();
        Money retained = ledger.retained(payment.amount());
        if (existing.status().refundedExternally()) {
            return PaymentCancelExternalRefundInfo.recorded(payment.id(), existing.claimId(), Reason.CLAIM_ALREADY_RECORDED, Channel.of(existing.status()),
                payment.status(), refunded, retained);
        }
        if (existing.status() == PaymentCancel.Status.SUCCEEDED) {
            return PaymentCancelExternalRefundInfo.alreadyRefunded(payment.id(), existing.claimId(), payment.status(), refunded, retained);
        }
        return null;
    }

    /**
     * 이 claimId 행 상태로 기록을 막는 이유. null 이면 PG 검증 후보다. 전이 가능 여부는 도메인 표({@link PaymentCancel.Status#canBecome})에 묻고,
     * 여기서는 시간 규칙만 본다 — PENDING 은 PG 재전송 기간 + 여유가 지나야, REJECTED 는 거절 유예가 지나야 한다. 시각을 모르면 닫힌 쪽(미확정)이다.
     */
    Reason refusal(PaymentCancel existing, Channel channel, Instant now) {
        if (existing == null) {
            return null;
        }
        if (!existing.status().canBecome(channel.status())) {
            return existing.status() == PaymentCancel.Status.VOIDED ? Reason.CLAIM_VOIDED : Reason.CLAIM_STATE_UNSUPPORTED;
        }
        return switch (existing.status()) {
            case PENDING -> elapsed(existing.createdAt(), stalePendingAfter(), now) ? null : Reason.CLAIM_REFUND_PENDING;
            case REJECTED -> rejectionSettled(existing, now) ? null : Reason.REJECTION_SETTLING;
            default -> null;
        };
    }

    /** PENDING 을 외부 확인 환불로 바꿀 수 있는 경과 시간 = PG 재전송 기간(14일) + PG 취소 호출 여유. 그 전에는 우리 PG 취소가 진행 중일 수 있다. */
    Duration stalePendingAfter() {
        return PaymentCancel.PG_RETRY_WINDOW.plus(properties.pendingCallMargin());
    }

    private boolean rejectionSettled(PaymentCancel rejected, Instant now) {
        return elapsed(rejected.updatedAt(), voidProperties.rejectionSettleGrace(), now);
    }

    private static boolean elapsed(Instant since, Duration wait, Instant now) {
        return since != null && !now.isBefore(since.plus(wait));
    }

    /**
     * 같은 결제에 아직 정착하지 않은 다른 claimId 의 취소가 있는가 — PENDING, 또는 거절 유예 안의 REJECTED.
     * 막 거절된 취소는 PG 잔액이 아직 정착하지 않았을 수 있어, 그 금액이 실제로 빠졌다면 이 claim 의 "원장에 없는 환불액"으로 흡수될 수 있다.
     */
    private boolean hasOtherUnsettled(List<PaymentCancel> all, long claimId, Instant now) {
        return all.stream().filter(c -> c.claimId() != claimId).anyMatch(c -> c.status() == PaymentCancel.Status.PENDING
            || (c.status() == PaymentCancel.Status.REJECTED && !rejectionSettled(c, now)));
    }

    private static boolean sameClaim(PaymentCancel existing, long paymentId, Money amount) {
        return existing.paymentId() == paymentId && existing.amount().equals(amount);
    }

    private static Decided unconfirmed(Payment payment, long claimId, Reason reason, PaymentCancelLedger ledger) {
        return new Decided(unconfirmedInfo(payment, claimId, reason, ledger));
    }

    private static PaymentCancelExternalRefundInfo unconfirmedInfo(Payment payment, long claimId, Reason reason, PaymentCancelLedger ledger) {
        return PaymentCancelExternalRefundInfo.unconfirmed(payment.id(), claimId, reason, payment.status(), ledger.refundedTotal(), ledger.retained(payment.amount()));
    }

    /** {@link #inspect} 의 결과 — 이미 정해진 판정이거나, PG 검증이 필요한 후보다. */
    public sealed interface Inspection permits Decided, Candidate { }

    /** {@link Candidate#resolve} 의 결과 — 기록하지 않는 판정이거나, PG 가 신고를 설명해 기록하러 갈 후보다. */
    public sealed interface Resolution permits Decided, Verified { }

    public record Decided(PaymentCancelExternalRefundInfo info) implements Inspection, Resolution { }

    /** PG 관측 잔액이 신고를 설명한 후보. {@code evidence} 는 {@code EXACT} 또는 {@code SURPLUS} 다. */
    public record Verified(Candidate candidate, Evidence evidence) implements Resolution {

        public Verified {
            Objects.requireNonNull(candidate, "candidate");
            if (evidence == null || !evidence.recordable()) {
                throw new IllegalArgumentException("기록할 수 있는 증거가 아니다: " + evidence);
            }
        }
    }

    /**
     * tx1 스냅숏. {@code claimStatus} 가 null 이면 이 claimId 행이 없었다.
     *
     * @param ledger 이 결제의 취소 합계 스냅숏 — PG 기대 잔액은 결제액 − pgCanceled 다
     */
    public record Candidate(Payment payment, long claimId, Money amount, Channel channel, PaymentCancel.Status claimStatus, PaymentCancelLedger ledger)
        implements Inspection {

        /** PG 잔액 대조에 넘길 기대 잔액 = 결제액 − pgCanceled. PG 밖 환불(OFF_PG)은 PG 잔액을 줄이지 않으므로 빼지 않는다. */
        public Money expectedBalance() {
            return ledger.expectedPgBalance(payment.amount());
        }

        /**
         * PG 조회 결과로 기록 여부를 정한다 — 무환불 판정의 {@code unconfirmedBy} 와 같은 자리다.
         * {@code UNKNOWN} → {@code PG_UNAVAILABLE}, {@code UNSUPPORTED} → {@code PG_CHECK_UNSUPPORTED}, 관측 잔액이 신고를 설명하지 못하면 {@code PG_LEDGER_MISMATCH}.
         * 설명하면(원장 산술은 {@link PaymentCancelLedger#externalRefundEvidence}) 기록하러 간다.
         */
        public Resolution resolve(Retention retention) {
            Objects.requireNonNull(retention, "retention");
            return switch (retention.outcome()) {
                case UNKNOWN -> new Decided(unconfirmed(Reason.PG_UNAVAILABLE));
                case UNSUPPORTED -> new Decided(unconfirmed(Reason.PG_CHECK_UNSUPPORTED));
                case MATCHED, MISMATCHED -> {
                    Evidence evidence = ledger.externalRefundEvidence(payment.amount(), retention.observedBalance(), amount, channel.status());
                    yield evidence.recordable() ? new Verified(this, evidence) : new Decided(unconfirmed(Reason.PG_LEDGER_MISMATCH));
                }
            };
        }

        /** PG 검증을 통과하지 못했을 때의 판정 — 기록하지 않는다. 금액은 tx1 원장 그대로다. */
        public PaymentCancelExternalRefundInfo unconfirmed(Reason reason) {
            return unconfirmedInfo(payment, claimId, reason, ledger);
        }
    }
}
