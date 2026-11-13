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

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelVoidResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelVoidInfo;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelVoidInfo.Reason;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgRetentionQueryResult;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationVoidFacade;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.global.properties.PaymentCancelVoidProperties;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 무환불 판정 (#691, {@code payment-guide.md} §8-1) — 판정 표 전 행 · PG 결과별 · tx2 스냅숏 재확인 · 재호출 멱등 · sealed 의미.
 * Facade 와 Processor 를 실제로 엮고 포트만 가짜로 둔다 — PG 를 부르지 않아야 하는 경우를 포트 수준에서 확인한다.
 */
class PaymentCancelVoidProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-11T05:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final long CLAIM = 42;

    private final PaymentCancelPersistencePort cancelPort = mock(PaymentCancelPersistencePort.class);
    private final PaymentGatewayPort gatewayPort = mock(PaymentGatewayPort.class);
    private final PaymentCancelVoidProcessor processor =
        new PaymentCancelVoidProcessor(cancelPort, new PaymentCancelVoidProperties(GRACE), Clock.fixed(NOW, ZoneOffset.UTC));
    private final PaymentCancellationVoidFacade facade =
        new PaymentCancellationVoidFacade(processor, new PaymentGatewayProcessor(gatewayPort), new PaymentInternalPresenter());
    private final Payment payment = PaymentFixtures.done();

    private void ledger(Payment locked, PaymentCancel claim, List<PaymentCancel> all) {
        given(cancelPort.lockPayment(locked.id())).willReturn(Optional.of(locked));
        given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.ofNullable(claim));
        given(cancelPort.findByPaymentId(locked.id())).willReturn(all);
    }

    /** MATCHED 는 기대 잔액 그대로, MISMATCHED 는 0원(콘솔 전액 취소)을 관측 잔액으로 답한다. */
    private void pg(PgRetentionQueryResult.State state) {
        given(gatewayPort.inquireRetention(any(), any())).willAnswer(invocation -> {
            Money expected = invocation.getArgument(1);
            return switch (state) {
                case MATCHED -> PgRetentionQueryResult.matched(expected);
                case MISMATCHED -> PgRetentionQueryResult.mismatched(Money.zero(expected.currency()));
                case UNKNOWN -> PgRetentionQueryResult.unknown();
                case UNSUPPORTED -> PgRetentionQueryResult.unsupported();
            };
        });
    }

    private PaymentCancelVoidResponse callVoid() {
        return callVoid(10_000);
    }

    private PaymentCancelVoidResponse callVoid(long amount) {
        return facade.voidCancellation(payment.id(), payment.orderId(), CLAIM, amount, CurrencyCode.KRW);
    }

    private static PaymentCancel claim(PaymentCancel.Status status) {
        return new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID, krw(10_000), status, NOW.minusSeconds(3_600), NOW.minusSeconds(3_600));
    }

    private static PaymentCancel rejectedAt(Instant updatedAt) {
        return new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID, krw(10_000), PaymentCancel.Status.REJECTED, NOW.minusSeconds(3_600), updatedAt);
    }

    private void assertNoPgNoSeal() {
        verify(gatewayPort, never()).inquireRetention(any(), any());
        verify(cancelPort, never()).insertVoided(any());
    }

    private static void assertCode(Runnable call, PaymentErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static void assertUnconfirmed(PaymentCancelVoidResponse result, String reason, boolean sealed) {
        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.sealed()).as("sealed").isEqualTo(sealed);
    }

    @Nested
    @DisplayName("무환불 확정 · 봉인")
    class Confirmed {

        @Test
        @DisplayName("행이 없고 PG 잔액이 원장과 같으면 VOIDED 를 넣고 NO_REFUND_CONFIRMED · CLAIM_NEVER_REQUESTED · sealed 다")
        void neverRequested() {
            ledger(payment, null, List.of());
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            assertThat(result.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(result.reason()).isEqualTo("CLAIM_NEVER_REQUESTED");
            assertThat(result.sealed()).isTrue();
            assertThat(result.paymentId()).isEqualTo(String.valueOf(payment.id()));
            assertThat(result.claimId()).isEqualTo("42");
            assertThat(result.paymentStatus().code()).isEqualTo("DONE");
            assertThat(result.retainedAmount().amount()).isEqualTo(129_000);
            verify(gatewayPort).inquireRetention(payment, krw(129_000));
            verify(cancelPort).insertVoided(PaymentCancel.voided(CLAIM, payment.id(), krw(10_000)));
        }

        @Test
        @DisplayName("거절 뒤 유예(60초)가 지났으면 PG_REJECTED 로 확정하고 행을 쓰지 않는다 — 경계(정확히 60초)는 지난 것으로 본다")
        void rejectedAfterGrace() {
            ledger(payment, rejectedAt(NOW.minus(GRACE)), List.of(rejectedAt(NOW.minus(GRACE))));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            assertThat(result.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(result.reason()).isEqualTo("PG_REJECTED");
            assertThat(result.sealed()).isTrue();
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("이미 VOIDED 면 다시 불러도 PG 를 다시 대조하고 같은 판정이다 — 새 행을 쓰지 않는다")
        void voidedIsIdempotent() {
            PaymentCancel voided = claim(PaymentCancel.Status.VOIDED);
            ledger(payment, voided, List.of(voided));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse first = callVoid();
            PaymentCancelVoidResponse second = callVoid();

            assertThat(first).isEqualTo(second);
            assertThat(first.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(first.reason()).isEqualTo("CLAIM_NEVER_REQUESTED");
            verify(gatewayPort, org.mockito.Mockito.times(2)).inquireRetention(payment, krw(129_000));
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("기대 잔액은 결제액 − pgCanceled(SUCCEEDED 합계) 다 — VOIDED · REJECTED 금액은 빼지 않는다")
        void expectedBalanceIgnoresVoidedAndRejected() {
            Payment partial = payment.recordCancellation(krw(29_000));
            ledger(partial, null, List.of(
                new PaymentCancel(41, partial.id(), krw(29_000), PaymentCancel.Status.SUCCEEDED),
                new PaymentCancel(40, partial.id(), krw(50_000), PaymentCancel.Status.VOIDED),
                new PaymentCancel(39, partial.id(), krw(20_000), PaymentCancel.Status.REJECTED)));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            verify(gatewayPort).inquireRetention(partial, krw(100_000));
            assertThat(result.retainedAmount().amount()).isEqualTo(100_000);
            assertThat(result.paymentStatus().code()).isEqualTo("PARTIAL_CANCELED");
            assertThat(result.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
        }

        @Test
        @DisplayName("남은 금액이 환불액과 같으면(경계) 후보로 통과해 확정한다")
        void retainedEqualToAmountPasses() {
            Payment partial = payment.recordCancellation(krw(119_000));
            ledger(partial, null, List.of(new PaymentCancel(41, partial.id(), krw(119_000), PaymentCancel.Status.SUCCEEDED)));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            verify(gatewayPort).inquireRetention(partial, krw(10_000));
            assertThat(result.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(result.retainedAmount().amount()).isEqualTo(10_000);
        }
    }

    @Nested
    @DisplayName("PG 를 부르지 않는 판정")
    class LocalDecision {

        @Test
        @DisplayName("SUCCEEDED 면 REFUNDED · CLAIM_REFUND_SUCCEEDED 이고 PG 를 부르지 않는다")
        void refunded() {
            Payment partial = payment.recordCancellation(krw(10_000));
            PaymentCancel succeeded = claim(PaymentCancel.Status.SUCCEEDED);
            ledger(partial, succeeded, List.of(succeeded));

            PaymentCancelVoidResponse result = callVoid();

            assertThat(result.outcome()).isEqualTo("REFUNDED");
            assertThat(result.reason()).isEqualTo("CLAIM_REFUND_SUCCEEDED");
            assertThat(result.sealed()).isFalse();
            assertThat(result.retainedAmount().amount()).isEqualTo(119_000);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("PENDING 이면 UNCONFIRMED · CLAIM_REFUND_PENDING · sealed=false 다")
        void pending() {
            PaymentCancel pending = claim(PaymentCancel.Status.PENDING);
            ledger(payment, pending, List.of(pending));

            assertUnconfirmed(callVoid(), "CLAIM_REFUND_PENDING", false);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("거절 기록 뒤 유예 안이면 UNCONFIRMED · REJECTION_SETTLING 이고, REJECTED 행이 있으니 sealed=true 다")
        void rejectedWithinGrace() {
            PaymentCancel rejected = rejectedAt(NOW.minusSeconds(59));
            ledger(payment, rejected, List.of(rejected));

            assertUnconfirmed(callVoid(), "REJECTION_SETTLING", true);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("거절 시각을 모르면(updatedAt 없음) 유예 안으로 본다 — 닫힌 쪽")
        void rejectedWithoutTimestampIsSettling() {
            PaymentCancel rejected = rejectedAt(null);
            ledger(payment, rejected, List.of(rejected));

            assertUnconfirmed(callVoid(), "REJECTION_SETTLING", true);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("같은 결제에 다른 클레임의 PENDING 이 있으면 UNCONFIRMED · OTHER_CANCEL_PENDING 이다")
        void otherPending() {
            ledger(payment, null, List.of(new PaymentCancel(43, payment.id(), krw(5_000), PaymentCancel.Status.PENDING)));

            assertUnconfirmed(callVoid(), "OTHER_CANCEL_PENDING", false);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("다른 클레임 전액 환불로 남은 금액이 0 이면 RETAINED_SHORT 다 — CANCELED 결제여도 PG 를 부르지 않는다")
        void fullyRefundedByOtherClaimIsRetainedShort() {
            Payment canceled = payment.recordCancellation(krw(129_000));
            ledger(canceled, null, List.of(new PaymentCancel(41, canceled.id(), krw(129_000), PaymentCancel.Status.SUCCEEDED)));

            PaymentCancelVoidResponse result = callVoid();

            assertUnconfirmed(result, "RETAINED_SHORT", false);
            assertThat(result.retainedAmount().amount()).isZero();
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("남은 금액이 환불액보다 1원이라도 적으면 RETAINED_SHORT 다 — VOIDED 행이 있으면 sealed=true 로 답한다")
        void retainedOneShort() {
            Payment partial = payment.recordCancellation(krw(119_001));
            PaymentCancel voided = claim(PaymentCancel.Status.VOIDED);
            ledger(partial, voided, List.of(voided, new PaymentCancel(41, partial.id(), krw(119_001), PaymentCancel.Status.SUCCEEDED)));

            assertUnconfirmed(callVoid(), "RETAINED_SHORT", true);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("요청 환불액이 결제액보다 크면 RETAINED_SHORT 다")
        void amountAbovePayment() {
            ledger(payment, null, List.of());

            assertUnconfirmed(callVoid(129_001), "RETAINED_SHORT", false);
            assertNoPgNoSeal();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelVoidProcessorTest#unsupportedPayments")
        @DisplayName("결제 상태가 DONE · PARTIAL_CANCELED · CANCELED 가 아니면 UNCONFIRMED · PAYMENT_STATE_UNSUPPORTED 다")
        void unsupportedPaymentState(String name, Payment unsupported) {
            ledger(unsupported, null, List.of());

            PaymentCancelVoidResponse result = facade.voidCancellation(unsupported.id(), unsupported.orderId(), CLAIM, 10_000, CurrencyCode.KRW);

            assertUnconfirmed(result, "PAYMENT_STATE_UNSUPPORTED", false);
            assertThat(result.paymentStatus().code()).isEqualTo(unsupported.status().name());
            assertNoPgNoSeal();
        }
    }

    static Stream<Arguments> unsupportedPayments() {
        return Stream.of(
            Arguments.of("LATE_APPROVED", PaymentFixtures.lateApproved()),
            Arguments.of("READY", PaymentFixtures.ready()),
            Arguments.of("IN_PROGRESS", PaymentFixtures.inProgress()),
            Arguments.of("UNKNOWN", PaymentFixtures.unknown()),
            Arguments.of("FAILED", PaymentFixtures.failed()));
    }

    @Nested
    @DisplayName("PG 잔액 대조 결과")
    class PgResult {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"MISMATCHED, PG_LEDGER_MISMATCH", "UNKNOWN, PG_UNAVAILABLE", "UNSUPPORTED, PG_CHECK_UNSUPPORTED"})
        @DisplayName("MATCHED 가 아니면 UNCONFIRMED 이고 봉인하지 않는다 — 행이 없었으니 sealed=false")
        void notMatched(PgRetentionQueryResult.State state, String reason) {
            ledger(payment, null, List.of());
            pg(state);

            assertUnconfirmed(callVoid(), reason, false);
            verify(cancelPort, never()).insertVoided(any());
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"MISMATCHED, PG_LEDGER_MISMATCH", "UNKNOWN, PG_UNAVAILABLE", "UNSUPPORTED, PG_CHECK_UNSUPPORTED"})
        @DisplayName("VOIDED 뒤 다시 불러 PG 가 MATCHED 가 아니면 UNCONFIRMED 이지만 sealed=true 다 — VOIDED 행은 그대로 둔다")
        void voidedThenNotMatched(PgRetentionQueryResult.State state, String reason) {
            PaymentCancel voided = claim(PaymentCancel.Status.VOIDED);
            ledger(payment, voided, List.of(voided));
            pg(state);

            assertUnconfirmed(callVoid(), reason, true);
            verify(cancelPort, never()).insertVoided(any());
            verify(cancelPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("tx2 스냅숏 재확인")
    class Recheck {

        @Test
        @DisplayName("PG 조회 사이에 이 claimId 의 PENDING 이 생기면 LEDGER_CHANGED 이고 INSERT 하지 않는다")
        void claimRowAppeared() {
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty(), Optional.of(claim(PaymentCancel.Status.PENDING)));
            given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(), List.of(claim(PaymentCancel.Status.PENDING)));
            pg(PgRetentionQueryResult.State.MATCHED);

            assertUnconfirmed(callVoid(), "LEDGER_CHANGED", false);
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("PG 조회 사이에 동시 void 가 같은 결제 · 금액 · 통화로 먼저 봉인했으면 같은 판정(NO_REFUND_CONFIRMED)이고 INSERT 하지 않는다")
        void concurrentVoidSealedFirst() {
            PaymentCancel voided = claim(PaymentCancel.Status.VOIDED);
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty(), Optional.of(voided));
            given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(), List.of(voided));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            assertThat(result.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(result.reason()).isEqualTo("CLAIM_NEVER_REQUESTED");
            assertThat(result.sealed()).isTrue();
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("사이에 생긴 VOIDED 가 다른 금액이면 동시 void 로 보지 않고 LEDGER_CHANGED 다")
        void voidedWithOtherAmountAppeared() {
            PaymentCancel foreign = new PaymentCancel(CLAIM, payment.id(), krw(9_000), PaymentCancel.Status.VOIDED);
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty(), Optional.of(foreign));
            given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(), List.of(foreign));
            pg(PgRetentionQueryResult.State.MATCHED);

            assertUnconfirmed(callVoid(), "LEDGER_CHANGED", true);
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("PG 취소 합계(SUCCEEDED)가 바뀌면 LEDGER_CHANGED 이고 바뀐 잔액 · 상태로 답한다")
        void succeededSumChanged() {
            Payment partial = payment.recordCancellation(krw(5_000));
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment), Optional.of(partial));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty());
            given(cancelPort.findByPaymentId(payment.id()))
                .willReturn(List.of(), List.of(new PaymentCancel(43, payment.id(), krw(5_000), PaymentCancel.Status.SUCCEEDED)));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            assertUnconfirmed(result, "LEDGER_CHANGED", false);
            assertThat(result.retainedAmount().amount()).isEqualTo(124_000);
            assertThat(result.paymentStatus().code()).isEqualTo("PARTIAL_CANCELED");
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("다른 클레임의 PENDING 이 생기면 LEDGER_CHANGED 다")
        void otherPendingAppeared() {
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty());
            given(cancelPort.findByPaymentId(payment.id()))
                .willReturn(List.of(), List.of(new PaymentCancel(43, payment.id(), krw(5_000), PaymentCancel.Status.PENDING)));
            pg(PgRetentionQueryResult.State.MATCHED);

            assertUnconfirmed(callVoid(), "LEDGER_CHANGED", false);
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("VOIDED 엄격 INSERT 가 경합에 지면 409 PAYMENT_012 가 그대로 나간다")
        void insertCollision() {
            ledger(payment, null, List.of());
            pg(PgRetentionQueryResult.State.MATCHED);
            given(cancelPort.insertVoided(any())).willThrow(new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT));

            assertCode(PaymentCancelVoidProcessorTest.this::callVoid, PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
    }

    @Nested
    @DisplayName("오류")
    class Errors {

        @Test
        @DisplayName("결제가 없으면 404 PAYMENT_001 이다")
        void notFound() {
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.empty());

            assertCode(PaymentCancelVoidProcessorTest.this::callVoid, PaymentErrorCode.NOT_FOUND_PAYMENT);
            assertNoPgNoSeal();
        }

        @ParameterizedTest(name = "claimId={0}")
        @ValueSource(longs = {0, -1})
        @DisplayName("claimId 가 양수가 아니면 DB · PG 전에 409 PAYMENT_019 다(결제 후 취소 Facade 와 같다)")
        void nonPositiveClaim(long claimId) {
            assertCode(() -> facade.voidCancellation(payment.id(), payment.orderId(), claimId, 10_000, CurrencyCode.KRW),
                PaymentErrorCode.CANCELLATION_KEY_REUSED);
            verifyNoInteractions(cancelPort, gatewayPort);
        }

        @Test
        @DisplayName("결제의 주문이 요청 orderId 와 다르면 409 PAYMENT_019 다 — 다른 주문의 결제를 가리켰다")
        void otherOrdersPayment() {
            ledger(payment, null, List.of());

            assertCode(() -> facade.voidCancellation(payment.id(), payment.orderId() + 1, CLAIM, 10_000, CurrencyCode.KRW),
                PaymentErrorCode.CANCELLATION_KEY_REUSED);
            assertNoPgNoSeal();
            verify(cancelPort, never()).findByClaimId(CLAIM);
        }

        @Test
        @DisplayName("자동 취소가 가진 claimId 면 409 PAYMENT_019 다")
        void autoClaim() {
            ledger(payment, null, List.of());
            given(cancelPort.isAutoClaim(CLAIM)).willReturn(true);

            assertCode(PaymentCancelVoidProcessorTest.this::callVoid, PaymentErrorCode.CANCELLATION_KEY_REUSED);
            assertNoPgNoSeal();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelVoidProcessorTest#foreignRows")
        @DisplayName("이 claimId 행과 결제 · 금액 · 통화가 다르면 409 PAYMENT_019 다")
        void keyReused(String name, PaymentCancel row) {
            ledger(payment, row, List.of());

            assertCode(PaymentCancelVoidProcessorTest.this::callVoid, PaymentErrorCode.CANCELLATION_KEY_REUSED);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("통화가 결제와 다르면 409 PAYMENT_017 이다 — 결제 후 취소 준비처럼 기존 행 대조(019)보다 먼저다")
        void currencyMismatchBeforeExistingRow() {
            ledger(payment, new PaymentCancel(CLAIM, payment.id(), krw(9_000), PaymentCancel.Status.VOIDED), List.of());

            assertCode(() -> facade.voidCancellation(payment.id(), payment.orderId(), CLAIM, 10_000, CurrencyCode.USD),
                PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
            assertNoPgNoSeal();
        }
    }

    static Stream<Arguments> foreignRows() {
        return Stream.of(
            Arguments.of("다른 결제", new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID + 1, krw(10_000), PaymentCancel.Status.VOIDED)),
            Arguments.of("다른 금액", new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID, krw(9_000), PaymentCancel.Status.VOIDED)),
            Arguments.of("다른 통화", new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID, Money.of(10_000, CurrencyCode.USD), PaymentCancel.Status.REJECTED)));
    }

    @Nested
    @DisplayName("#693 외부 확인 환불 · 두 합계")
    class ExternalRefund {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PaymentCancel.Status.class, names = {"EXTERNAL_PG_REFUNDED", "OFF_PG_REFUNDED"})
        @DisplayName("이 claimId 가 외부 확인 환불이면 REFUNDED · CLAIM_REFUNDED_EXTERNALLY 이고 PG 를 부르지 않는다 — 남은 금액은 결제액 − refundedTotal")
        void externallyRefundedClaimIsRefunded(PaymentCancel.Status status) {
            Payment locked = status.reducesPgBalance() ? payment.recordCancellation(krw(10_000)) : payment;
            PaymentCancel refunded = claim(status);
            ledger(locked, refunded, List.of(refunded));

            PaymentCancelVoidResponse result = callVoid();

            assertThat(result.outcome()).isEqualTo("REFUNDED");
            assertThat(result.reason()).isEqualTo("CLAIM_REFUNDED_EXTERNALLY");
            assertThat(result.sealed()).isFalse();
            assertThat(result.paymentStatus().code()).isEqualTo(locked.status().name());
            assertThat(result.retainedAmount().amount()).isEqualTo(119_000);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("PG 기대 잔액은 OFF_PG 를 빼지 않고(결제액 − pgCanceled), 남은 금액은 OFF_PG 까지 뺀다(결제액 − refundedTotal)")
        void expectedBalanceExcludesOffPgButRetainedIncludesIt() {
            Payment partial = payment.recordCancellation(krw(20_000));
            ledger(partial, null, List.of(
                new PaymentCancel(40, partial.id(), krw(20_000), PaymentCancel.Status.EXTERNAL_PG_REFUNDED),
                new PaymentCancel(41, partial.id(), krw(30_000), PaymentCancel.Status.OFF_PG_REFUNDED)));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            verify(gatewayPort).inquireRetention(partial, krw(109_000));
            assertThat(result.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(result.retainedAmount().amount()).isEqualTo(79_000);
            verify(cancelPort).insertVoided(PaymentCancel.voided(CLAIM, partial.id(), krw(10_000)));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"MISMATCHED, PG_LEDGER_MISMATCH", "UNKNOWN, PG_UNAVAILABLE", "UNSUPPORTED, PG_CHECK_UNSUPPORTED"})
        @DisplayName("PG 가 MATCHED 가 아닐 때의 남은 금액도 결제액 − refundedTotal 이다 — PG 기대 잔액을 답하지 않는다")
        void notMatchedAnswersRetainedNotExpectedBalance(PgRetentionQueryResult.State state, String reason) {
            ledger(payment, null, List.of(new PaymentCancel(41, payment.id(), krw(30_000), PaymentCancel.Status.OFF_PG_REFUNDED)));
            pg(state);

            PaymentCancelVoidResponse result = callVoid();

            assertUnconfirmed(result, reason, false);
            verify(gatewayPort).inquireRetention(payment, krw(129_000));
            assertThat(result.retainedAmount().amount()).isEqualTo(99_000);
        }

        @Test
        @DisplayName("RETAINED_SHORT 는 OFF_PG 를 포함한 환불 합계로 본다 — 남은 금액 = 환불액이면 통과, 1원 적으면 PG 를 부르지 않는다")
        void retainedShortCountsOffPg() {
            ledger(payment, null, List.of(new PaymentCancel(41, payment.id(), krw(119_000), PaymentCancel.Status.OFF_PG_REFUNDED)));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse boundary = callVoid();

            assertThat(boundary.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
            assertThat(boundary.retainedAmount().amount()).isEqualTo(10_000);
            verify(gatewayPort).inquireRetention(payment, krw(129_000));

            org.mockito.Mockito.clearInvocations(gatewayPort, cancelPort);
            ledger(payment, null, List.of(new PaymentCancel(41, payment.id(), krw(119_001), PaymentCancel.Status.OFF_PG_REFUNDED)));

            PaymentCancelVoidResponse shortBy1 = callVoid();

            assertUnconfirmed(shortBy1, "RETAINED_SHORT", false);
            assertThat(shortBy1.retainedAmount().amount()).isEqualTo(9_999);
            assertNoPgNoSeal();
        }

        @Test
        @DisplayName("PG 조회 사이에 OFF_PG 기록이 생기면 PG 합계가 같아도 원장이 바뀐 것이다 — LEDGER_CHANGED 이고 봉인하지 않는다")
        void offPgAppearedDuringInquiry() {
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty());
            given(cancelPort.findByPaymentId(payment.id()))
                .willReturn(List.of(), List.of(new PaymentCancel(43, payment.id(), krw(5_000), PaymentCancel.Status.OFF_PG_REFUNDED)));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            assertUnconfirmed(result, "LEDGER_CHANGED", false);
            assertThat(result.retainedAmount().amount()).isEqualTo(124_000);
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("PG 조회 사이에 EXTERNAL_PG 기록이 생겨 pgCanceled 가 바뀌면 LEDGER_CHANGED 이고 봉인하지 않는다 — 결제 상태가 아직 같아도")
        void externalPgAppearedDuringInquiry() {
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty());
            given(cancelPort.findByPaymentId(payment.id()))
                .willReturn(List.of(), List.of(new PaymentCancel(43, payment.id(), krw(5_000), PaymentCancel.Status.EXTERNAL_PG_REFUNDED)));
            pg(PgRetentionQueryResult.State.MATCHED);

            PaymentCancelVoidResponse result = callVoid();

            verify(gatewayPort).inquireRetention(payment, krw(129_000));
            assertUnconfirmed(result, "LEDGER_CHANGED", false);
            assertThat(result.retainedAmount().amount()).isEqualTo(124_000);
            verify(cancelPort, never()).insertVoided(any());
        }

        @Test
        @DisplayName("PG 관측 잔액은 MATCHED · MISMATCHED 에만 있고, UNKNOWN · UNSUPPORTED 에는 없다")
        void observedBalanceOnlyWithEvidence() {
            assertThat(PgRetentionQueryResult.mismatched(krw(0)).observedBalance()).isEqualTo(krw(0));
            assertThat(PgRetentionQueryResult.unknown().observedBalance()).isNull();
            assertThatThrownBy(() -> new PgRetentionQueryResult(PgRetentionQueryResult.State.MATCHED, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PgRetentionQueryResult(PgRetentionQueryResult.State.UNSUPPORTED, krw(1)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PgRetentionQueryResult.matched(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("환불됨 판정은 두 이유로만 만든다")
        void refundedReasonIsRestricted() {
            assertThat(PaymentCancelVoidInfo.refunded(1, CLAIM, Reason.CLAIM_REFUNDED_EXTERNALLY, PaymentStatus.DONE, krw(1)).outcome())
                .isEqualTo(PaymentCancelVoidInfo.Outcome.REFUNDED);
            assertThatThrownBy(() -> PaymentCancelVoidInfo.refunded(1, CLAIM, Reason.CLAIM_REFUND_PENDING, PaymentStatus.DONE, krw(1)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "PENDING, CLAIM_REFUND_PENDING",
        "SUCCEEDED, CLAIM_REFUND_SUCCEEDED",
        "REJECTED, PG_REJECTED",
        "VOIDED, CLAIM_NEVER_REQUESTED",
        "EXTERNAL_PG_REFUNDED, CLAIM_REFUNDED_EXTERNALLY",
        "OFF_PG_REFUNDED, CLAIM_REFUNDED_EXTERNALLY"})
    @DisplayName("상태별 판정 이유 전수 — 외부 확인 환불 두 상태는 CLAIM_REFUNDED_EXTERNALLY(REFUNDED) 다")
    void classifyEveryStatus(PaymentCancel.Status status, Reason expected) {
        PaymentCancel row = new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID, krw(10_000), status, NOW.minusSeconds(3_600), NOW.minusSeconds(3_600));

        assertThat(PaymentCancelVoidProcessor.classify(row, NOW, GRACE)).isEqualTo(expected);
        assertThat(Stream.of(PaymentCancel.Status.values()).map(Enum::name)).as("표가 모든 상태를 덮는다")
            .containsExactlyInAnyOrder("PENDING", "SUCCEEDED", "REJECTED", "VOIDED", "EXTERNAL_PG_REFUNDED", "OFF_PG_REFUNDED");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PaymentCancel.Status.class)
    @DisplayName("후보(PG 대조로 갈 수 있는 판정)는 행 없음 · VOIDED · 유예 지난 REJECTED 뿐이다 — 외부 확인 환불(#693)은 후보가 아니다")
    void onlyVoidedAndSettledRejectedCanBecomeCandidates(PaymentCancel.Status status) {
        PaymentCancel row = new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID, krw(10_000), status, NOW.minusSeconds(3_600), NOW.minusSeconds(3_600));

        Reason reason = PaymentCancelVoidProcessor.classify(row, NOW, GRACE);

        boolean candidate = reason == Reason.CLAIM_NEVER_REQUESTED || reason == Reason.PG_REJECTED;
        assertThat(candidate).as("%s → %s", status, reason)
            .isEqualTo(status == PaymentCancel.Status.VOIDED || status == PaymentCancel.Status.REJECTED);
        assertThat(status.refusesRefund()).isEqualTo(candidate);
        assertThat(PaymentCancelVoidProcessor.classify(null, NOW, GRACE)).isEqualTo(Reason.CLAIM_NEVER_REQUESTED);
    }

    @Test
    @DisplayName("무환불 확정은 봉인 없이 만들 수 없다 — 반대로 미확정은 봉인과 함께 답할 수 있다")
    void confirmedImpliesSealed() {
        assertThatThrownBy(() -> new PaymentCancelVoidInfo(1, CLAIM, PaymentCancelVoidInfo.Outcome.NO_REFUND_CONFIRMED, Reason.CLAIM_NEVER_REQUESTED,
            PaymentStatus.DONE, krw(1), false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentCancelVoidInfo.unconfirmed(1, CLAIM, Reason.PG_UNAVAILABLE, PaymentStatus.DONE, krw(1), true).sealed()).isTrue();
    }

    @Test
    @DisplayName("유예 설정은 없으면 60초, 0 이하는 기동 실패다")
    void graceProperty() {
        assertThat(new PaymentCancelVoidProperties(null).rejectionSettleGrace()).isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> new PaymentCancelVoidProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentCancelVoidProperties(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
