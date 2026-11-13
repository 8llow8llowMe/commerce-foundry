package com.commercefoundry.domainlayer.payment.application.service.processor;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationFacade;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentCancelProcessorTest {
    private final PaymentCancelPersistencePort cancelPort = mock(PaymentCancelPersistencePort.class);
    private final PaymentRepositoryPort paymentPort = mock(PaymentRepositoryPort.class);
    private final PaymentCancelProcessor processor = new PaymentCancelProcessor(cancelPort, paymentPort, Clock.systemUTC());
    private final Payment payment = PaymentFixtures.done();

    @Test
    void reservesSameClaimAndAmountWithoutDuplicateLedgerRow() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(pending));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));

        var prepared = processor.prepare(payment.id(), 42, krw(10_000));

        assertThat(prepared.cancel()).isEqualTo(pending);
        assertThat(prepared.cumulative()).isEqualTo(krw(10_000));
        verifyNoInteractions(paymentPort);
    }

    @Test
    void rejectsSameClaimWithAnotherAmountBeforePgCall() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));

        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(9_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_KEY_REUSED));
    }

    @Test
    void rejectsOverRefundAndAnotherPendingClaim() {
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(
            new PaymentCancel(41, payment.id(), krw(120_000), PaymentCancel.Status.SUCCEEDED)));
        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(10_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID));

        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(
            new PaymentCancel(43, payment.id(), krw(1_000), PaymentCancel.Status.PENDING)));
        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(1_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
    }

    @Test
    void confirmedCancellationSavesPaymentAndLedgerTogether() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(129_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(pending));
        given(paymentPort.save(payment.recordCancellation(krw(129_000)))).willReturn(payment.recordCancellation(krw(129_000)));

        Payment result = processor.complete(payment.id(), 42);

        assertThat(result.status()).isEqualTo(PaymentStatus.CANCELED);
        verify(cancelPort).save(pending.succeeded());
    }

    @Test
    void permanentRejectionMarksLedgerAndSameClaimCannotCallPgAgain() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));
        processor.reject(payment.id(), 42);
        verify(cancelPort).save(pending.rejected());

        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending.rejected()));
        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(10_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
    }

    @Test
    void voidedClaimIsPermanentlyRefusedBeforePg() {
        // #691 봉인: 주문이 거절 · 재출고를 확정한 클레임으로는 환불을 보내지 않는다 — REJECTED 와 같은 016, PG 미호출
        PaymentCancel voided = PaymentCancel.voided(42, payment.id(), krw(10_000));
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(voided));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(voided));
        PaymentGatewayProcessor gateway = org.mockito.Mockito.mock(PaymentGatewayProcessor.class);
        var facade = new com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationFacade(processor, gateway);

        assertThatThrownBy(() -> facade.cancel(payment.id(), 42, 10_000, krw(1).currency(), "42"))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
        verifyNoInteractions(gateway);
        verify(cancelPort, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());

        // 확정 · 거절 경로도 VOIDED 를 PENDING 으로 보지 않는다
        assertThatThrownBy(() -> processor.complete(payment.id(), 42))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
        processor.reject(payment.id(), 42);
        verify(cancelPort, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(paymentPort);
    }

    @Test
    void voidedAmountIsNotCountedAsCanceled() {
        // 다른 클레임의 VOIDED 가 결제액 전부여도 새 클레임의 누계 한도에 들어가지 않는다
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(PaymentCancel.voided(41, payment.id(), krw(129_000))));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.empty());
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(129_000), PaymentCancel.Status.PENDING);
        given(cancelPort.save(pending)).willReturn(pending);

        var prepared = processor.prepare(payment.id(), 42, krw(129_000));

        assertThat(prepared.cumulative()).isEqualTo(krw(129_000));

        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(PaymentCancel.voided(41, payment.id(), krw(129_000)), pending));
        given(paymentPort.save(payment.recordCancellation(krw(129_000)))).willReturn(payment.recordCancellation(krw(129_000)));

        assertThat(processor.complete(payment.id(), 42).status()).isEqualTo(PaymentStatus.CANCELED);
    }

    private void ledger(Payment locked, PaymentCancel claim, List<PaymentCancel> all) {
        given(cancelPort.lockPayment(locked.id())).willReturn(Optional.of(locked));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.ofNullable(claim));
        given(cancelPort.findByPaymentId(locked.id())).willReturn(all);
    }

    private PaymentCancellationFacade facade(PaymentGatewayProcessor gateway) {
        return new PaymentCancellationFacade(processor, gateway);
    }

    private static void assertCode(Runnable call, PaymentErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PaymentCancel.Status.class, names = {"EXTERNAL_PG_REFUNDED", "OFF_PG_REFUNDED"})
    @DisplayName("#693 외부 확인 환불 행의 claimId 로 결제 후 취소가 오면 PG 를 부르지 않고 200 이다 — 원장 · 결제를 쓰지 않는다")
    void externallyRefundedClaimAnswersWithoutPg(PaymentCancel.Status status) {
        Payment partial = payment.recordCancellation(krw(10_000));
        PaymentCancel refunded = new PaymentCancel(42, payment.id(), krw(10_000), status);
        ledger(partial, refunded, List.of(refunded));
        PaymentGatewayProcessor gateway = mock(PaymentGatewayProcessor.class);

        var response = facade(gateway).cancel(payment.id(), 42, 10_000, CurrencyCode.KRW, "42");

        assertThat(response.claimId()).isEqualTo("42");
        assertThat(response.status()).isEqualTo(partial.status().name());
        assertThat(response.canceledAmount()).isEqualTo(10_000);
        verifyNoInteractions(gateway, paymentPort);
        verify(cancelPort, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PaymentCancel.Status.class, names = {"SUCCEEDED", "EXTERNAL_PG_REFUNDED", "OFF_PG_REFUNDED"})
    @DisplayName("환불 확정 행의 200 은 '같은 결제에 다른 클레임 PENDING 이면 016' 검사보다 먼저다 — order 재시도의 수습 경로")
    void refundedClaimAnswersBeforeOtherPendingCheck(PaymentCancel.Status status) {
        // 이 테스트가 지나는 분기는 다른 PENDING(016) 하나다. 14일 기한(018)은 이 claimId 행이 PENDING 일 때만, 결제 상태 검사는 행이 없을 때만 보므로
        // 순서와 무관하게 환불 확정 행에는 걸리지 않는다 — 오래된 createdAt · CANCELED 결제는 그 사실을 함께 고정할 뿐이다.
        Payment canceled = payment.recordCancellation(krw(129_000));
        PaymentCancel refunded = new PaymentCancel(42, payment.id(), krw(10_000), status, Instant.parse("2020-01-01T00:00:00Z"));
        PaymentCancel otherPending = new PaymentCancel(43, payment.id(), krw(1_000), PaymentCancel.Status.PENDING);
        ledger(canceled, refunded, List.of(refunded, otherPending));
        PaymentGatewayProcessor gateway = mock(PaymentGatewayProcessor.class);

        var prepared = processor.prepare(payment.id(), 42, krw(10_000));
        var response = facade(gateway).cancel(payment.id(), 42, 10_000, CurrencyCode.KRW, "42");

        assertThat(prepared.cancel()).isEqualTo(refunded);
        assertThat(prepared.cumulative()).as("PG 를 부르지 않으므로 지금의 pgCanceled")
            .isEqualTo(status.reducesPgBalance() ? krw(10_000) : krw(0));
        assertThat(response.status()).isEqualTo("CANCELED");
        verifyNoInteractions(gateway, paymentPort);
        verify(cancelPort, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PaymentCancel.Status.class, names = {"EXTERNAL_PG_REFUNDED", "OFF_PG_REFUNDED"})
    @DisplayName("외부 확인 환불 행과 금액 · 결제가 다르면 409 PAYMENT_019 다 — 200 보다 먼저 본다")
    void externallyRefundedClaimWithOtherAmountIsKeyReuse(PaymentCancel.Status status) {
        PaymentCancel refunded = new PaymentCancel(42, payment.id(), krw(10_000), status);
        ledger(payment, refunded, List.of(refunded));

        assertCode(() -> processor.prepare(payment.id(), 42, krw(9_000)), PaymentErrorCode.CANCELLATION_KEY_REUSED);

        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(new PaymentCancel(42, payment.id() + 1, krw(10_000), status)));
        assertCode(() -> processor.prepare(payment.id(), 42, krw(10_000)), PaymentErrorCode.CANCELLATION_KEY_REUSED);
    }

    @Test
    @DisplayName("새 클레임 한도(017)는 OFF_PG 를 포함한 환불 합계로 본다 — 경계(남은 금액과 같음)는 통과하고 누계는 OFF_PG 를 뺀 PG 누계다")
    void limitCountsOffPgButCumulativeDoesNot() {
        Payment partial = payment.recordCancellation(krw(20_000));
        ledger(partial, null, List.of(
            new PaymentCancel(40, payment.id(), krw(20_000), PaymentCancel.Status.EXTERNAL_PG_REFUNDED),
            new PaymentCancel(41, payment.id(), krw(100_000), PaymentCancel.Status.OFF_PG_REFUNDED)));

        assertCode(() -> processor.prepare(payment.id(), 42, krw(9_001)), PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
        verify(cancelPort, never()).save(any());

        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(9_000), PaymentCancel.Status.PENDING);
        given(cancelPort.save(pending)).willReturn(pending);
        var prepared = processor.prepare(payment.id(), 42, krw(9_000));

        assertThat(prepared.cancel()).isEqualTo(pending);
        assertThat(prepared.cumulative()).as("PG 취소 요청의 누계 = pgCanceled(20,000) + 9,000").isEqualTo(krw(29_000));
    }

    @Test
    @DisplayName("PENDING 재시도의 누계도 PG 누계다 — EXTERNAL_PG 는 넣고 OFF_PG 는 넣지 않는다")
    void pendingRetryCumulativeExcludesOffPg() {
        Payment partial = payment.recordCancellation(krw(20_000));
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        ledger(partial, pending, List.of(
            new PaymentCancel(40, payment.id(), krw(20_000), PaymentCancel.Status.EXTERNAL_PG_REFUNDED),
            new PaymentCancel(41, payment.id(), krw(30_000), PaymentCancel.Status.OFF_PG_REFUNDED),
            pending));

        assertThat(processor.prepare(payment.id(), 42, krw(10_000)).cumulative()).isEqualTo(krw(30_000));
    }

    @Test
    @DisplayName("확정(complete) 누계는 EXTERNAL_PG 를 포함하고 OFF_PG 는 뺀다 — 결제 상태는 PG 상태의 거울이다")
    void completeCumulativeIsPgCanceled() {
        Payment partial = payment.recordCancellation(krw(20_000));
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        ledger(partial, pending, List.of(
            new PaymentCancel(40, payment.id(), krw(20_000), PaymentCancel.Status.EXTERNAL_PG_REFUNDED),
            new PaymentCancel(41, payment.id(), krw(99_000), PaymentCancel.Status.OFF_PG_REFUNDED),
            pending));
        Payment expected = partial.recordCancellation(krw(30_000));
        given(paymentPort.save(expected)).willReturn(expected);

        Payment result = processor.complete(payment.id(), 42);

        assertThat(result.status()).as("OFF_PG 까지 더하면 129,000 이라 CANCELED 가 되지만, PG 잔액이 남았으니 PARTIAL_CANCELED 다")
            .isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        verify(paymentPort).save(expected);
        verify(cancelPort).save(pending.succeeded());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PaymentCancel.Status.class, names = {"EXTERNAL_PG_REFUNDED", "OFF_PG_REFUNDED"})
    @DisplayName("외부 확인 환불 행은 확정 · 거절 경로에서도 PENDING 으로 보지 않는다")
    void externallyRefundedClaimIsNeverCompletedOrRejected(PaymentCancel.Status status) {
        PaymentCancel refunded = new PaymentCancel(42, payment.id(), krw(10_000), status);
        ledger(payment, refunded, List.of(refunded));

        assertCode(() -> processor.complete(payment.id(), 42), PaymentErrorCode.CANCELLATION_CONFLICT);
        processor.reject(payment.id(), 42);

        verify(cancelPort, never()).save(any());
        verifyNoInteractions(paymentPort);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PaymentCancel.Status.class, names = {"REJECTED", "VOIDED"})
    @DisplayName("Facade 방어 — 준비가 PENDING 도 환불 확정도 아닌 행을 넘기면 PG 를 부르지 않고 016 이다")
    void facadeNeverOpensPgForNonPendingRow(PaymentCancel.Status status) {
        PaymentCancelProcessor stubbed = mock(PaymentCancelProcessor.class);
        PaymentCancel row = new PaymentCancel(42, payment.id(), krw(10_000), status);
        given(stubbed.prepare(payment.id(), 42, krw(10_000))).willReturn(new PaymentCancelProcessor.Prepared(payment, row, krw(10_000)));
        PaymentGatewayProcessor gateway = mock(PaymentGatewayProcessor.class);

        assertCode(() -> new PaymentCancellationFacade(stubbed, gateway).cancel(payment.id(), 42, 10_000, CurrencyCode.KRW, "42"),
            PaymentErrorCode.CANCELLATION_CONFLICT);
        verifyNoInteractions(gateway);
    }
}
