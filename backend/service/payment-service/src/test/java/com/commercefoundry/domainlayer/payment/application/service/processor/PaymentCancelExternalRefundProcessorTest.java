package com.commercefoundry.domainlayer.payment.application.service.processor;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelExternalRefundResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Reason;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgRetentionQueryResult;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationExternalRefundFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor.Retention;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor.RetentionOutcome;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel.Status;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.global.properties.PaymentCancelExternalRefundProperties;
import com.commercefoundry.global.properties.PaymentCancelVoidProperties;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 외부 확인 환불 기록 (#693, {@code payment-guide.md} §8) — 대상 claim 행 상태별 전이표 전 행 × 채널, PG 검증 경계, PG 결과별, tx1 · tx2 사이 원장 변화,
 * 멱등 재요청(PG 미호출), 오류 순서, 결제 상태 전이, 로그에 금액 없음. Facade 와 Processor 를 실제로 엮고 포트만 가짜로 둔다.
 */
class PaymentCancelExternalRefundProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-13T05:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(60);
    /** 14일 + 기본 여유 1시간. */
    private static final Duration STALE = Duration.ofDays(14).plusHours(1);
    private static final long CLAIM = 42;
    private static final long AMOUNT = 10_000;

    private final PaymentCancelPersistencePort cancelPort = mock(PaymentCancelPersistencePort.class);
    private final PaymentRepositoryPort paymentPort = mock(PaymentRepositoryPort.class);
    private final PaymentGatewayPort gatewayPort = mock(PaymentGatewayPort.class);
    private final PaymentCancelExternalRefundProcessor processor = new PaymentCancelExternalRefundProcessor(cancelPort, paymentPort,
        new PaymentCancelVoidProperties(GRACE), new PaymentCancelExternalRefundProperties(null), Clock.fixed(NOW, ZoneOffset.UTC));
    private final PaymentCancellationExternalRefundFacade facade =
        new PaymentCancellationExternalRefundFacade(processor, new PaymentGatewayProcessor(gatewayPort), new PaymentInternalPresenter());
    private final Payment payment = PaymentFixtures.done();

    private void ledger(Payment locked, PaymentCancel claim, List<PaymentCancel> all) {
        given(cancelPort.lockPayment(locked.id())).willReturn(Optional.of(locked));
        given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.ofNullable(claim));
        given(cancelPort.findByPaymentId(locked.id())).willReturn(all);
        given(paymentPort.save(any())).willAnswer(returnsFirstArg());
    }

    /** PG 가 이 잔액을 관측했다고 답한다 — 기대 잔액과 같으면 MATCHED, 다르면 MISMATCHED. */
    private void pgObserves(Function<Money, Money> observedFromExpected) {
        given(gatewayPort.inquireRetention(any(), any())).willAnswer(invocation -> {
            Money expected = invocation.getArgument(1);
            Money observed = observedFromExpected.apply(expected);
            return observed.equals(expected) ? PgRetentionQueryResult.matched(observed) : PgRetentionQueryResult.mismatched(observed);
        });
    }

    /** PG 잔액이 원장보다 정확히 이 금액만큼 줄었다(콘솔 환불). */
    private void pgRefundedOutside(long unexplained) {
        pgObserves(expected -> expected.minus(krw(unexplained)));
    }

    private PaymentCancelExternalRefundResponse call(Channel channel) {
        return call(AMOUNT, channel);
    }

    private PaymentCancelExternalRefundResponse call(long amount, Channel channel) {
        return facade.recordExternalRefund(payment.id(), payment.orderId(), CLAIM, amount, CurrencyCode.KRW, channel);
    }

    private static PaymentCancel claim(Status status) {
        return row(status, NOW.minus(STALE), NOW.minus(STALE));
    }

    private static PaymentCancel row(Status status, Instant createdAt, Instant updatedAt) {
        return new PaymentCancel(CLAIM, PaymentFixtures.PAYMENT_ID, krw(AMOUNT), status, createdAt, updatedAt);
    }

    private void assertNoPgNoWrite() {
        verify(gatewayPort, never()).inquireRetention(any(), any());
        verify(cancelPort, never()).recordExternalRefund(any());
        verify(cancelPort, never()).save(any());
        verify(paymentPort, never()).save(any());
    }

    private void assertNoWrite() {
        verify(cancelPort, never()).recordExternalRefund(any());
        verify(cancelPort, never()).save(any());
        verify(paymentPort, never()).save(any());
    }

    private static void assertUnconfirmed(PaymentCancelExternalRefundResponse result, Reason reason) {
        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(result.reason()).isEqualTo(reason.name());
        assertThat(result.channel()).isNull();
    }

    private static void assertRecorded(PaymentCancelExternalRefundResponse result, Reason reason, Channel channel) {
        assertThat(result.outcome()).isEqualTo("RECORDED");
        assertThat(result.reason()).isEqualTo(reason.name());
        assertThat(result.channel()).isEqualTo(channel.name());
    }

    private static void assertCode(Runnable call, PaymentErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Nested
    @DisplayName("대상 claim 행 상태별 전이 — 기록")
    class Records {

        @Test
        @DisplayName("행 없음 · PG 채널 · 원장에 없는 환불액 = 금액이면 EXTERNAL_PG_REFUNDED 를 엄격 INSERT 하고 결제를 PARTIAL_CANCELED 로 옮긴다")
        void newRowPgChannel() {
            ledger(payment, null, List.of());
            pgRefundedOutside(AMOUNT);

            PaymentCancelExternalRefundResponse result = call(Channel.PG);

            assertRecorded(result, Reason.PG_VERIFIED, Channel.PG);
            assertThat(result.paymentId()).isEqualTo(String.valueOf(payment.id()));
            assertThat(result.claimId()).isEqualTo("42");
            assertThat(result.paymentStatus().code()).isEqualTo("PARTIAL_CANCELED");
            assertThat(result.refundedAmount().amount()).isEqualTo(10_000);
            assertThat(result.retainedAmount().amount()).isEqualTo(119_000);
            verify(gatewayPort).inquireRetention(payment, krw(129_000));
            verify(cancelPort).recordExternalRefund(PaymentCancel.externallyRefunded(CLAIM, payment.id(), krw(AMOUNT), Status.EXTERNAL_PG_REFUNDED));
            verify(paymentPort).save(payment.recordCancellation(krw(10_000)));
        }

        @Test
        @DisplayName("PG 채널로 남은 금액 전부를 기록하면 결제는 CANCELED 다 — 누계는 pgCanceled + 금액(OFF_PG 제외)")
        void pgChannelFullAmountCancelsPayment() {
            Payment partial = payment.recordCancellation(krw(29_000));
            ledger(partial, null, List.of(new PaymentCancel(41, partial.id(), krw(29_000), Status.SUCCEEDED)));
            pgRefundedOutside(100_000);

            PaymentCancelExternalRefundResponse result = call(100_000, Channel.PG);

            assertRecorded(result, Reason.PG_VERIFIED, Channel.PG);
            assertThat(result.paymentStatus().code()).isEqualTo("CANCELED");
            assertThat(result.retainedAmount().amount()).isZero();
            verify(gatewayPort).inquireRetention(partial, krw(100_000));
            verify(paymentPort).save(partial.recordCancellation(krw(129_000)));
        }

        @Test
        @DisplayName("행 없음 · OFF_PG · PG 잔액이 원장 그대로면 OFF_PG_REFUNDED 를 넣고 결제 상태는 그대로(DONE) 다")
        void newRowOffPgKeepsPaymentStatus() {
            ledger(payment, null, List.of());
            pgRefundedOutside(0);

            PaymentCancelExternalRefundResponse result = call(Channel.OFF_PG);

            assertRecorded(result, Reason.PG_VERIFIED, Channel.OFF_PG);
            assertThat(result.paymentStatus().code()).isEqualTo("DONE");
            assertThat(result.refundedAmount().amount()).isEqualTo(10_000);
            assertThat(result.retainedAmount().amount()).isEqualTo(119_000);
            verify(cancelPort).recordExternalRefund(PaymentCancel.externallyRefunded(CLAIM, payment.id(), krw(AMOUNT), Status.OFF_PG_REFUNDED));
            verify(paymentPort, never()).save(any());
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessorTest#updatableRows")
        @DisplayName("기존 행 UPDATE — 유예 지난 REJECTED(두 채널) · PG 채널의 VOIDED · 14일 + 여유가 지난 PENDING(두 채널)")
        void existingRowIsUpdated(Status from, Channel channel) {
            PaymentCancel existing = claim(from);
            ledger(payment, existing, List.of(existing));
            pgRefundedOutside(channel == Channel.PG ? AMOUNT : 0);

            PaymentCancelExternalRefundResponse result = call(channel);

            assertRecorded(result, Reason.PG_VERIFIED, channel);
            verify(cancelPort).recordExternalRefund(existing.externallyRefunded(channel.status()));
            assertThat(result.paymentStatus().code()).isEqualTo(channel == Channel.PG ? "PARTIAL_CANCELED" : "DONE");
        }

        @Test
        @DisplayName("REJECTED 유예 경계 — 정확히 60초면 지난 것으로 본다")
        void rejectedGraceBoundary() {
            PaymentCancel rejected = row(Status.REJECTED, NOW.minus(STALE), NOW.minus(GRACE));
            ledger(payment, rejected, List.of(rejected));
            pgRefundedOutside(AMOUNT);

            assertRecorded(call(Channel.PG), Reason.PG_VERIFIED, Channel.PG);
        }

        @Test
        @DisplayName("PENDING 경계 — 생성 뒤 정확히 14일 + 1시간이면 PG 검증으로 간다")
        void stalePendingBoundary() {
            PaymentCancel pending = row(Status.PENDING, NOW.minus(STALE), NOW.minus(STALE));
            ledger(payment, pending, List.of(pending));
            pgRefundedOutside(AMOUNT);

            assertRecorded(call(Channel.PG), Reason.PG_VERIFIED, Channel.PG);
        }
    }

    static Stream<Arguments> updatableRows() {
        return Stream.of(
            Arguments.of(Status.REJECTED, Channel.PG),
            Arguments.of(Status.REJECTED, Channel.OFF_PG),
            Arguments.of(Status.VOIDED, Channel.PG),
            Arguments.of(Status.PENDING, Channel.PG),
            Arguments.of(Status.PENDING, Channel.OFF_PG));
    }

    @Nested
    @DisplayName("대상 claim 행 상태별 전이 — tx1 에서 바로 답함(PG 미호출 · 쓰지 않음)")
    class Refusals {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Channel.class)
        @DisplayName("생성 14일 + 1시간 안의 PENDING 은 UNCONFIRMED · CLAIM_REFUND_PENDING — 1초 모자라도 막는다")
        void freshPending(Channel channel) {
            PaymentCancel pending = row(Status.PENDING, NOW.minus(STALE).plusSeconds(1), NOW.minus(STALE));
            ledger(payment, pending, List.of(pending));

            assertUnconfirmed(call(channel), Reason.CLAIM_REFUND_PENDING);
            assertNoPgNoWrite();
        }

        @Test
        @DisplayName("생성 시각을 모르는 PENDING 은 닫힌 쪽(CLAIM_REFUND_PENDING)이다")
        void pendingWithoutCreatedAt() {
            PaymentCancel pending = row(Status.PENDING, null, null);
            ledger(payment, pending, List.of(pending));

            assertUnconfirmed(call(Channel.PG), Reason.CLAIM_REFUND_PENDING);
            assertNoPgNoWrite();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Channel.class)
        @DisplayName("유예(60초) 안의 REJECTED 는 UNCONFIRMED · REJECTION_SETTLING — 거절 시각을 모르면 같다")
        void rejectionSettling(Channel channel) {
            for (Instant rejectedAt : new Instant[] {NOW.minus(GRACE).plusSeconds(1), null}) {
                PaymentCancel rejected = row(Status.REJECTED, NOW.minus(STALE), rejectedAt);
                ledger(payment, rejected, List.of(rejected));

                assertUnconfirmed(call(channel), Reason.REJECTION_SETTLING);
            }
            assertNoPgNoWrite();
        }

        @Test
        @DisplayName("VOIDED 위 PG 밖 신고는 UNCONFIRMED · CLAIM_VOIDED — PG 증거로만 바꾼다")
        void voidedOffPg() {
            PaymentCancel voided = claim(Status.VOIDED);
            ledger(payment, voided, List.of(voided));

            assertUnconfirmed(call(Channel.OFF_PG), Reason.CLAIM_VOIDED);
            assertNoPgNoWrite();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Channel.class)
        @DisplayName("SUCCEEDED 는 200 ALREADY_REFUNDED · CLAIM_REFUND_SUCCEEDED · channel null 이고 쓰지 않는다")
        void succeeded(Channel channel) {
            PaymentCancel succeeded = claim(Status.SUCCEEDED);
            Payment partial = payment.recordCancellation(krw(AMOUNT));
            ledger(partial, succeeded, List.of(succeeded));

            PaymentCancelExternalRefundResponse result = call(channel);

            assertThat(result.outcome()).isEqualTo("ALREADY_REFUNDED");
            assertThat(result.reason()).isEqualTo("CLAIM_REFUND_SUCCEEDED");
            assertThat(result.channel()).isNull();
            assertThat(result.refundedAmount().amount()).isEqualTo(10_000);
            assertThat(result.retainedAmount().amount()).isEqualTo(119_000);
            assertNoPgNoWrite();
        }

        @ParameterizedTest(name = "기록 {0} · 요청 {1}")
        @MethodSource("com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessorTest#recordedRows")
        @DisplayName("이미 외부 확인 환불이면 PG 를 다시 조회하지 않고 RECORDED · CLAIM_ALREADY_RECORDED · 기록된 채널로 답한다 — 요청 채널이 달라도 같다")
        void alreadyRecordedIsIdempotent(Status recorded, Channel requested) {
            PaymentCancel row = claim(recorded);
            Payment locked = recorded.reducesPgBalance() ? payment.recordCancellation(krw(AMOUNT)) : payment;
            ledger(locked, row, List.of(row));

            PaymentCancelExternalRefundResponse first = call(requested);
            PaymentCancelExternalRefundResponse again = call(requested);

            assertThat(again).isEqualTo(first);
            assertRecorded(first, Reason.CLAIM_ALREADY_RECORDED, Channel.of(recorded));
            assertThat(first.retainedAmount().amount()).isEqualTo(119_000);
            assertNoPgNoWrite();
        }

        @Test
        @DisplayName("PG 채널 전액 기록으로 결제가 CANCELED 가 된 뒤의 재요청도 RECORDED 다 — 멱등 판정이 결제 상태보다 먼저다")
        void idempotentAfterFullPgRefund() {
            PaymentCancel row = new PaymentCancel(CLAIM, payment.id(), krw(129_000), Status.EXTERNAL_PG_REFUNDED);
            ledger(payment.recordCancellation(krw(129_000)), row, List.of(row));

            PaymentCancelExternalRefundResponse result = call(129_000, Channel.PG);

            assertRecorded(result, Reason.CLAIM_ALREADY_RECORDED, Channel.PG);
            assertThat(result.paymentStatus().code()).isEqualTo("CANCELED");
            assertNoPgNoWrite();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessorTest#unsupportedPayments")
        @DisplayName("결제 상태가 DONE · PARTIAL_CANCELED 가 아니면 PAYMENT_STATE_UNSUPPORTED 다")
        void paymentStateUnsupported(String name, Payment locked) {
            Payment target = locked;
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(target));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.empty());
            given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of());

            assertUnconfirmed(call(Channel.PG), Reason.PAYMENT_STATE_UNSUPPORTED);
            assertNoPgNoWrite();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Channel.class)
        @DisplayName("같은 결제에 다른 claimId 의 PENDING 이 있으면 OTHER_CANCEL_PENDING — 오래된 PENDING 이어도 같다")
        void otherPending(Channel channel) {
            PaymentCancel other = new PaymentCancel(41, payment.id(), krw(5_000), Status.PENDING, NOW.minus(Duration.ofDays(30)));
            ledger(payment, null, List.of(other));

            assertUnconfirmed(call(channel), Reason.OTHER_CANCEL_PENDING);
            assertNoPgNoWrite();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Channel.class)
        @DisplayName("다른 claimId 의 REJECTED 가 거절 유예 안이면(시각 모름 포함) OTHER_CANCEL_PENDING — 막 거절된 금액을 이 claim 이 흡수하지 않게")
        void otherRejectionSettling(Channel channel) {
            for (Instant rejectedAt : new Instant[] {NOW.minus(GRACE).plusSeconds(1), null}) {
                PaymentCancel other = new PaymentCancel(41, payment.id(), krw(5_000), Status.REJECTED, NOW.minus(STALE), rejectedAt);
                ledger(payment, null, List.of(other));

                assertUnconfirmed(call(channel), Reason.OTHER_CANCEL_PENDING);
            }
            assertNoPgNoWrite();
        }

        @Test
        @DisplayName("다른 claimId 의 REJECTED 가 유예를 지났으면(정확히 60초 경계) 막지 않는다")
        void otherRejectionSettled() {
            PaymentCancel other = new PaymentCancel(41, payment.id(), krw(5_000), Status.REJECTED, NOW.minus(STALE), NOW.minus(GRACE));
            ledger(payment, null, List.of(other));
            pgRefundedOutside(AMOUNT);

            assertRecorded(call(Channel.PG), Reason.PG_VERIFIED, Channel.PG);
        }

        @Test
        @DisplayName("RETAINED_SHORT 는 OFF_PG 를 포함한 환불 합계로 본다 — 남은 금액 = 금액이면 통과, 1원 모자라면 PG 를 부르지 않는다")
        void retainedShortCountsOffPg() {
            List<PaymentCancel> others = List.of(
                new PaymentCancel(40, payment.id(), krw(100_000), Status.OFF_PG_REFUNDED),
                new PaymentCancel(39, payment.id(), krw(19_000), Status.SUCCEEDED));
            ledger(payment.recordCancellation(krw(19_000)), null, others);
            pgRefundedOutside(AMOUNT);

            assertRecorded(call(AMOUNT, Channel.PG), Reason.PG_VERIFIED, Channel.PG);
            verify(gatewayPort).inquireRetention(any(), any());

            PaymentCancelExternalRefundResponse result = call(AMOUNT + 1, Channel.PG);
            assertUnconfirmed(result, Reason.RETAINED_SHORT);
            assertThat(result.refundedAmount().amount()).isEqualTo(119_000);
            assertThat(result.retainedAmount().amount()).isEqualTo(10_000);
        }
    }

    static Stream<Arguments> recordedRows() {
        return Stream.of(
            Arguments.of(Status.EXTERNAL_PG_REFUNDED, Channel.PG),
            Arguments.of(Status.EXTERNAL_PG_REFUNDED, Channel.OFF_PG),
            Arguments.of(Status.OFF_PG_REFUNDED, Channel.OFF_PG),
            Arguments.of(Status.OFF_PG_REFUNDED, Channel.PG));
    }

    static Stream<Arguments> unsupportedPayments() {
        return Stream.of(
            Arguments.of("READY", PaymentFixtures.ready()),
            Arguments.of("UNKNOWN", PaymentFixtures.unknown()),
            Arguments.of("FAILED", PaymentFixtures.failed()),
            Arguments.of("LATE_APPROVED", PaymentFixtures.lateApproved()),
            Arguments.of("CANCELED", PaymentFixtures.done().recordCancellation(krw(129_000))));
    }

    @Nested
    @DisplayName("PG 검증 — 원장에 없는 환불액 = (결제액 − pgCanceled) − 관측 잔액")
    class PgVerification {

        @ParameterizedTest(name = "원장에 없는 환불액 {0}")
        @ValueSource(longs = {AMOUNT, AMOUNT + 1, 50_000})
        @DisplayName("PG 채널은 원장에 없는 환불액 ≥ 금액이면 기록한다(같음 경계 포함)")
        void pgChannelAtOrAboveAmount(long unexplained) {
            ledger(payment, null, List.of());
            pgRefundedOutside(unexplained);

            assertRecorded(call(Channel.PG), Reason.PG_VERIFIED, Channel.PG);
        }

        @ParameterizedTest(name = "원장에 없는 환불액 {0}")
        @ValueSource(longs = {AMOUNT - 1, 1, 0})
        @DisplayName("PG 채널은 원장에 없는 환불액 < 금액이면 PG_LEDGER_MISMATCH — 금액 − 1 경계, PG MATCHED(0) 도 여기다")
        void pgChannelBelowAmount(long unexplained) {
            ledger(payment, null, List.of());
            pgRefundedOutside(unexplained);

            assertUnconfirmed(call(Channel.PG), Reason.PG_LEDGER_MISMATCH);
            assertNoWrite();
        }

        @ParameterizedTest(name = "원장에 없는 환불액 {0}")
        @ValueSource(longs = {1, AMOUNT})
        @DisplayName("OFF_PG 는 원장에 없는 환불액이 0 이 아니면 PG_LEDGER_MISMATCH — 콘솔 환불을 PG 밖으로 잘못 신고한 경우")
        void offPgRequiresZeroUnexplained(long unexplained) {
            ledger(payment, null, List.of());
            pgRefundedOutside(unexplained);

            assertUnconfirmed(call(Channel.OFF_PG), Reason.PG_LEDGER_MISMATCH);
            assertNoWrite();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Channel.class)
        @DisplayName("PG 잔액이 원장 기대보다 크면(원장의 PG 취소가 PG 에 없음) 어느 채널도 기록하지 않는다")
        void observedAboveExpected(Channel channel) {
            Payment partial = payment.recordCancellation(krw(29_000));
            ledger(partial, null, List.of(new PaymentCancel(41, partial.id(), krw(29_000), Status.SUCCEEDED)));
            pgObserves(expected -> expected.plus(krw(1)));

            assertUnconfirmed(call(channel), Reason.PG_LEDGER_MISMATCH);
            assertNoWrite();
        }

        @Test
        @DisplayName("기대 잔액은 결제액 − pgCanceled 다 — OFF_PG · VOIDED · REJECTED 는 빼지 않고 EXTERNAL_PG 는 뺀다")
        void expectedBalanceUsesPgCanceled() {
            Payment partial = payment.recordCancellation(krw(25_000));
            ledger(partial, null, List.of(
                new PaymentCancel(41, partial.id(), krw(20_000), Status.SUCCEEDED),
                new PaymentCancel(40, partial.id(), krw(5_000), Status.EXTERNAL_PG_REFUNDED),
                new PaymentCancel(39, partial.id(), krw(30_000), Status.OFF_PG_REFUNDED),
                new PaymentCancel(38, partial.id(), krw(7_000), Status.VOIDED),
                new PaymentCancel(37, partial.id(), krw(3_000), Status.REJECTED, NOW.minus(STALE), NOW.minus(GRACE))));
            pgRefundedOutside(AMOUNT);

            PaymentCancelExternalRefundResponse result = call(Channel.PG);

            verify(gatewayPort).inquireRetention(partial, krw(104_000));
            assertRecorded(result, Reason.PG_VERIFIED, Channel.PG);
            assertThat(result.refundedAmount().amount()).isEqualTo(65_000);
            assertThat(result.retainedAmount().amount()).isEqualTo(64_000);
            verify(paymentPort).save(partial.recordCancellation(krw(35_000)));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessorTest#pgWithoutEvidence")
        @DisplayName("PG UNKNOWN → PG_UNAVAILABLE, UNSUPPORTED → PG_CHECK_UNSUPPORTED — 쓰지 않는다")
        void pgWithoutEvidence(PgRetentionQueryResult answer, Reason reason) {
            ledger(payment, null, List.of());
            given(gatewayPort.inquireRetention(any(), any())).willReturn(answer);

            for (Channel channel : Channel.values()) {
                PaymentCancelExternalRefundResponse result = call(channel);
                assertUnconfirmed(result, reason);
                assertThat(result.retainedAmount().amount()).isEqualTo(129_000);
            }
            assertNoWrite();
        }
    }

    static Stream<Arguments> pgWithoutEvidence() {
        return Stream.of(
            Arguments.of(PgRetentionQueryResult.unknown(), Reason.PG_UNAVAILABLE),
            Arguments.of(PgRetentionQueryResult.unsupported(), Reason.PG_CHECK_UNSUPPORTED));
    }

    @Nested
    @DisplayName("tx2 재확인 — PG 조회 사이 원장 변화")
    class LedgerChanged {

        private void secondSnapshot(Payment second, PaymentCancel claimSecond, List<PaymentCancel> allSecond, PaymentCancel claimFirst, List<PaymentCancel> allFirst) {
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment), Optional.of(second));
            given(cancelPort.findByClaimId(CLAIM)).willReturn(Optional.ofNullable(claimFirst), Optional.ofNullable(claimSecond));
            given(cancelPort.findByPaymentId(payment.id())).willReturn(allFirst, allSecond);
        }

        @Test
        @DisplayName("다른 클레임의 PENDING 이 생기면 LEDGER_CHANGED 이고 쓰지 않는다")
        void otherPendingAppeared() {
            secondSnapshot(payment, null, List.of(new PaymentCancel(41, payment.id(), krw(1_000), Status.PENDING)), null, List.of());
            pgRefundedOutside(AMOUNT);

            assertUnconfirmed(call(Channel.PG), Reason.LEDGER_CHANGED);
            assertNoWrite();
        }

        @Test
        @DisplayName("OFF_PG 기록이 생기면 pgCanceled 가 같아도 LEDGER_CHANGED 다")
        void offPgAppeared() {
            secondSnapshot(payment, null, List.of(new PaymentCancel(41, payment.id(), krw(1_000), Status.OFF_PG_REFUNDED)), null, List.of());
            pgRefundedOutside(0);

            PaymentCancelExternalRefundResponse result = call(Channel.OFF_PG);

            assertUnconfirmed(result, Reason.LEDGER_CHANGED);
            assertThat(result.refundedAmount().amount()).isEqualTo(1_000);
            assertNoWrite();
        }

        @Test
        @DisplayName("다른 클레임의 SUCCEEDED 가 생겨 pgCanceled · 결제 상태가 바뀌면 LEDGER_CHANGED 이고 바뀐 값으로 답한다")
        void succeededAppeared() {
            Payment changed = payment.recordCancellation(krw(5_000));
            secondSnapshot(changed, null, List.of(new PaymentCancel(41, payment.id(), krw(5_000), Status.SUCCEEDED)), null, List.of());
            pgRefundedOutside(AMOUNT);

            PaymentCancelExternalRefundResponse result = call(Channel.PG);

            assertUnconfirmed(result, Reason.LEDGER_CHANGED);
            assertThat(result.paymentStatus().code()).isEqualTo("PARTIAL_CANCELED");
            assertThat(result.retainedAmount().amount()).isEqualTo(124_000);
            assertNoWrite();
        }

        @Test
        @DisplayName("이 claimId 의 행이 생기면(같은 클레임 결제 후 취소 준비 PENDING) LEDGER_CHANGED 다")
        void claimRowAppeared() {
            PaymentCancel pending = row(Status.PENDING, NOW, NOW);
            secondSnapshot(payment, pending, List.of(pending), null, List.of());
            pgRefundedOutside(AMOUNT);

            assertUnconfirmed(call(Channel.PG), Reason.LEDGER_CHANGED);
            assertNoWrite();
        }

        @Test
        @DisplayName("같은 요청이 먼저 기록했으면(같은 결제 · 금액의 외부 확인 환불 행) 쓰지 않고 RECORDED · CLAIM_ALREADY_RECORDED 로 답한다")
        void concurrentSameRequestRecordedFirst() {
            PaymentCancel recorded = row(Status.EXTERNAL_PG_REFUNDED, NOW, NOW);
            secondSnapshot(payment.recordCancellation(krw(AMOUNT)), recorded, List.of(recorded), null, List.of());
            pgRefundedOutside(AMOUNT);

            assertRecorded(call(Channel.PG), Reason.CLAIM_ALREADY_RECORDED, Channel.PG);
            assertNoWrite();
        }

        @Test
        @DisplayName("같은 claimId 를 다른 결제로 먼저 썼으면 LEDGER_CHANGED 다 — 다시 부르면 019")
        void sameClaimOtherPayment() {
            PaymentCancel foreign = new PaymentCancel(CLAIM, payment.id() + 1, krw(AMOUNT), Status.OFF_PG_REFUNDED);
            secondSnapshot(payment, foreign, List.of(), null, List.of());
            pgRefundedOutside(AMOUNT);

            assertUnconfirmed(call(Channel.PG), Reason.LEDGER_CHANGED);
            assertNoWrite();
        }

        @Test
        @DisplayName("기록 INSERT 가 경합에 지면 409 PAYMENT_012 가 그대로 나간다")
        void insertCollision() {
            ledger(payment, null, List.of());
            pgRefundedOutside(AMOUNT);
            given(cancelPort.recordExternalRefund(any())).willThrow(new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT));

            assertCode(() -> call(Channel.PG), PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
            verify(paymentPort, never()).save(any());
        }

        @Test
        @DisplayName("PG 조회 사이 다른 claimId 가 PENDING → REJECTED 로 끝나 유예 안이면 원장 합계가 같아도 LEDGER_CHANGED 다")
        void otherRejectionAppeared() {
            PaymentCancel rejected = new PaymentCancel(41, payment.id(), krw(5_000), Status.REJECTED, NOW, NOW);
            secondSnapshot(payment, null, List.of(rejected), null, List.of());
            pgRefundedOutside(AMOUNT);

            assertUnconfirmed(call(Channel.PG), Reason.LEDGER_CHANGED);
            assertNoWrite();
        }

        @Test
        @DisplayName("PG 조회 사이 이 claimId 의 우리 PG 취소가 SUCCEEDED 가 되면 쓰지 않고 ALREADY_REFUNDED · CLAIM_REFUND_SUCCEEDED 다")
        void sameClaimSucceededMeanwhile() {
            PaymentCancel pending = claim(Status.PENDING);
            PaymentCancel succeeded = row(Status.SUCCEEDED, NOW.minus(STALE), NOW);
            secondSnapshot(payment.recordCancellation(krw(AMOUNT)), succeeded, List.of(succeeded), pending, List.of(pending));
            pgRefundedOutside(AMOUNT);

            PaymentCancelExternalRefundResponse result = call(Channel.PG);

            assertThat(result.outcome()).isEqualTo("ALREADY_REFUNDED");
            assertThat(result.reason()).isEqualTo("CLAIM_REFUND_SUCCEEDED");
            assertThat(result.channel()).isNull();
            assertNoWrite();
        }
    }

    @Test
    @DisplayName("PG 채널에서 원장에 없는 환불액이 신고 금액보다 크면 기록하되 정해진 코드의 WARN 을 남긴다 — 금액은 남기지 않는다")
    @ExtendWith(OutputCaptureExtension.class)
    void surplusIsRecordedWithAnomalySignal(CapturedOutput output) {
        ledger(payment, null, List.of());
        pgRefundedOutside(AMOUNT + 2_345);

        assertRecorded(call(Channel.PG), Reason.PG_VERIFIED, Channel.PG);

        assertThat(output.getAll()).contains("WARN", PaymentCancelExternalRefundProcessor.EXTERNAL_REFUND_SURPLUS, "claimId=42")
            .doesNotContain("12345").doesNotContain("2345").doesNotContain("10000");
        verify(cancelPort).recordExternalRefund(any());
    }

    @Test
    @DisplayName("원장에 없는 환불액 = 금액이면 이상 신호를 남기지 않는다")
    @ExtendWith(OutputCaptureExtension.class)
    void exactLeavesNoSurplusSignal(CapturedOutput output) {
        ledger(payment, null, List.of());
        pgRefundedOutside(AMOUNT);

        assertRecorded(call(Channel.PG), Reason.PG_VERIFIED, Channel.PG);

        assertThat(output.getAll()).doesNotContain(PaymentCancelExternalRefundProcessor.EXTERNAL_REFUND_SURPLUS);
    }

    @Nested
    @DisplayName("오류 순서")
    class Errors {

        @ParameterizedTest
        @ValueSource(longs = {0, -1})
        @DisplayName("claimId 가 양수가 아니면 DB · PG 전에 409 PAYMENT_019 다")
        void nonPositiveClaim(long claimId) {
            assertCode(() -> facade.recordExternalRefund(payment.id(), payment.orderId(), claimId, AMOUNT, CurrencyCode.KRW, Channel.PG),
                PaymentErrorCode.CANCELLATION_KEY_REUSED);
            verifyNoInteractions(cancelPort, gatewayPort, paymentPort);
        }

        @Test
        @DisplayName("결제가 없으면 404 PAYMENT_001 이다")
        void notFound() {
            given(cancelPort.lockPayment(payment.id())).willReturn(Optional.empty());

            assertCode(() -> call(Channel.PG), PaymentErrorCode.NOT_FOUND_PAYMENT);
            verifyNoInteractions(gatewayPort);
        }

        @Test
        @DisplayName("결제의 주문이 다르면 409 PAYMENT_019 — 자동 취소 claimId · 금액 검사보다 먼저다")
        void otherOrder() {
            ledger(payment, null, List.of());
            given(cancelPort.isAutoClaim(CLAIM)).willReturn(true);

            assertCode(() -> facade.recordExternalRefund(payment.id(), payment.orderId() + 1, CLAIM, AMOUNT, CurrencyCode.USD, Channel.PG),
                PaymentErrorCode.CANCELLATION_KEY_REUSED);
            verify(cancelPort, never()).isAutoClaim(CLAIM);
            assertNoPgNoWrite();
        }

        @Test
        @DisplayName("자동 취소가 가진 claimId 면 409 PAYMENT_019 — 통화 검사보다 먼저다")
        void autoClaim() {
            ledger(payment, null, List.of());
            given(cancelPort.isAutoClaim(CLAIM)).willReturn(true);

            assertCode(() -> facade.recordExternalRefund(payment.id(), payment.orderId(), CLAIM, AMOUNT, CurrencyCode.USD, Channel.PG),
                PaymentErrorCode.CANCELLATION_KEY_REUSED);
            assertNoPgNoWrite();
        }

        @Test
        @DisplayName("통화가 결제와 다르면 409 PAYMENT_017 — 기존 행 대조(019)보다 먼저다")
        void currencyBeforeExistingRow() {
            ledger(payment, new PaymentCancel(CLAIM, payment.id() + 1, krw(1), Status.PENDING), List.of());

            assertCode(() -> facade.recordExternalRefund(payment.id(), payment.orderId(), CLAIM, AMOUNT, CurrencyCode.USD, Channel.PG),
                PaymentErrorCode.CANCELLATION_AMOUNT_INVALID);
            verify(cancelPort, never()).findByClaimId(CLAIM);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Status.class)
        @DisplayName("이 claimId 행과 결제 · 금액이 다르면 상태와 무관하게 409 PAYMENT_019 다 — 멱등 응답보다 먼저다")
        void existingRowMismatch(Status status) {
            ledger(payment, new PaymentCancel(CLAIM, payment.id(), krw(AMOUNT + 1), status), List.of());

            assertCode(() -> call(Channel.PG), PaymentErrorCode.CANCELLATION_KEY_REUSED);
            assertNoPgNoWrite();
        }
    }

    @Nested
    @DisplayName("도메인 전이 표 · 설정")
    class Domain {

        @Test
        @DisplayName("허용 전이는 PENDING → 결과 4개, REJECTED → 외부 2개, VOIDED → EXTERNAL_PG 뿐이다 — 나머지는 전부 거절")
        void transitionTable() {
            Set<String> allowed = Set.of(
                "PENDING>SUCCEEDED", "PENDING>REJECTED", "PENDING>EXTERNAL_PG_REFUNDED", "PENDING>OFF_PG_REFUNDED",
                "REJECTED>EXTERNAL_PG_REFUNDED", "REJECTED>OFF_PG_REFUNDED", "VOIDED>EXTERNAL_PG_REFUNDED");
            for (Status from : Status.values()) {
                for (Status to : Status.values()) {
                    assertThat(from.canBecome(to)).as(from + ">" + to).isEqualTo(allowed.contains(from + ">" + to));
                }
            }
        }

        @Test
        @DisplayName("외부 확인 환불 도메인 메서드는 표 밖 전이 · 외부 상태가 아닌 목표를 거절한다")
        void domainMethodRefusals() {
            for (Status from : EnumSet.of(Status.SUCCEEDED, Status.EXTERNAL_PG_REFUNDED, Status.OFF_PG_REFUNDED)) {
                for (Status to : EnumSet.of(Status.EXTERNAL_PG_REFUNDED, Status.OFF_PG_REFUNDED)) {
                    assertThatThrownBy(() -> claim(from).externallyRefunded(to)).as(from + ">" + to).isInstanceOf(IllegalStateException.class);
                }
            }
            assertThatThrownBy(() -> claim(Status.VOIDED).externallyRefunded(Status.OFF_PG_REFUNDED)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> claim(Status.PENDING).externallyRefunded(Status.SUCCEEDED)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PaymentCancel.externallyRefunded(CLAIM, 1, krw(1), Status.VOIDED)).isInstanceOf(IllegalArgumentException.class);
            assertThat(claim(Status.REJECTED).externallyRefunded(Status.OFF_PG_REFUNDED).status()).isEqualTo(Status.OFF_PG_REFUNDED);
            assertThatThrownBy(() -> claim(Status.REJECTED).succeeded()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("PENDING 여유 설정은 없으면 1시간, 1시간 미만은 기동 실패다 — 기준은 14일 + 여유")
        void marginProperty() {
            assertThat(new PaymentCancelExternalRefundProperties(null).pendingCallMargin()).isEqualTo(Duration.ofHours(1));
            assertThat(new PaymentCancelExternalRefundProperties(Duration.ofHours(6)).pendingCallMargin()).isEqualTo(Duration.ofHours(6));
            assertThatThrownBy(() -> new PaymentCancelExternalRefundProperties(Duration.ofMinutes(59))).isInstanceOf(IllegalArgumentException.class);
            assertThat(processor.stalePendingAfter()).isEqualTo(STALE);
        }

        @Test
        @DisplayName("응답 정보는 기록됨에만 채널을 싣는다")
        void channelOnlyWhenRecorded() {
            assertThatThrownBy(() -> PaymentCancelExternalRefundInfo.unconfirmed(1, 2, Reason.PG_VERIFIED, payment.status(), krw(0), krw(1)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PaymentCancelExternalRefundInfo.recorded(1, 2, Reason.RETAINED_SHORT, Channel.PG, payment.status(), krw(0), krw(1)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Retention(RetentionOutcome.UNKNOWN, krw(1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Retention(RetentionOutcome.MATCHED, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("기록 로그에는 식별자 · 채널만 남는다 — 금액은 남기지 않는다")
    @ExtendWith(OutputCaptureExtension.class)
    void logsCarryNoAmount(CapturedOutput output) {
        ledger(payment, null, List.of());
        pgRefundedOutside(12_345);

        assertRecorded(call(12_345, Channel.PG), Reason.PG_VERIFIED, Channel.PG);

        assertThat(output.getAll()).contains("external refund recorded", "claimId=42", "channel=PG")
            .doesNotContain("12345").doesNotContain("12,345").doesNotContain("129000").doesNotContain("116655");
    }
}
