package com.commercefoundry.domainlayer.payment.application.service;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVAL_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_NAME;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.approval;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.web.presenter.PaymentWebPresenter;
import com.commercefoundry.domainlayer.payment.application.command.PaymentConfirmCommand;
import com.commercefoundry.domainlayer.payment.application.command.PaymentPrepareCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException.Kind;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentRetryLaterException;
import com.commercefoundry.domainlayer.payment.application.model.PayableOrder;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalOutcome;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebUseCase.PrepareResult;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderSummaryProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.global.properties.PaymentGateProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 결제 준비 · 승인 분기 — 활성 시도 규칙, 멱등 키(같은 키 재요청은 저장 없음 · 키 재사용 409), 금액 불일치는 PG 미호출, 버전 충돌 재조회, PG 결과별 전이 · 응답.
 * Processor 는 목이다 — 트랜잭션 · 저장은 {@code PaymentProcessorTest} · 통합 테스트가 본다.
 */
class PaymentWebFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-21T01:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-10-21T01:30:00Z");
    private static final UUID KEY = UUID.fromString(APPROVAL_KEY);
    private static final UUID OTHER_KEY = UUID.fromString("9a1b2c3d-0000-4000-8000-000000000003");
    private static final String PG_ORDER_ID = Long.toString(PAYMENT_ID);
    private static final PayableOrder ORDER = new PayableOrder(ORDER_ID, krw(129_000), ORDER_NAME, DUE_AT);
    private static final PaymentConfirmCommand CONFIRM = new PaymentConfirmCommand(PAYMENT_KEY, PG_ORDER_ID, 129_000, "KRW");

    private OrderSummaryProcessor orderSummaryProcessor;
    private PaymentProcessor paymentProcessor;
    private PaymentGatewayProcessor paymentGatewayProcessor;
    private PaymentWebFacade facade;

    @BeforeEach
    void setUp() {
        orderSummaryProcessor = mock(OrderSummaryProcessor.class);
        paymentProcessor = mock(PaymentProcessor.class);
        paymentGatewayProcessor = mock(PaymentGatewayProcessor.class);
        facade = new PaymentWebFacade(orderSummaryProcessor, paymentProcessor, paymentGatewayProcessor, new PaymentWebPresenter(),
            new PaymentGateProperties(true), Clock.fixed(NOW, ZoneOffset.UTC));
        given(orderSummaryProcessor.getPayableOrder(ORDER_ID, MEMBER_ID)).willReturn(ORDER);
        given(orderSummaryProcessor.getConfirmableOrder(ORDER_ID, MEMBER_ID)).willReturn(ORDER);
        given(paymentProcessor.findByApprovalKey(anyLong(), anyString())).willReturn(Optional.empty());
    }

    private static void assertError(ThrowingCallable call, PaymentErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    @Nested
    @DisplayName("결제 준비")
    class Prepare {

        private PrepareResult prepare() {
            return facade.prepare(MEMBER_ID, new PaymentPrepareCommand(ORDER_ID));
        }

        @Test
        @DisplayName("활성 시도가 없으면 새 READY — 201(created) · 결제 기한을 싣는다")
        void createsNew() {
            Payment created = PaymentFixtures.ready();
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.empty());
            given(paymentProcessor.prepare(MEMBER_ID, ORDER)).willReturn(created);

            PrepareResult result = prepare();

            assertThat(result.created()).isTrue();
            assertThat(result.payment().pgOrderId()).isEqualTo(created.pgOrderId());
            assertThat(result.payment().paymentDueAt()).isEqualTo(DUE_AT);
        }

        @Test
        @DisplayName("같은 금액 · 주문명의 READY 는 그대로 200 — 저장하지 않는다")
        void returnsSameReady() {
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.of(PaymentFixtures.ready()));

            PrepareResult result = prepare();

            assertThat(result.created()).isFalse();
            assertThat(result.payment().paymentId()).isEqualTo(Long.toString(PAYMENT_ID));
            verify(paymentProcessor, never()).prepare(anyLong(), any());
            verify(paymentProcessor, never()).replace(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("결제키 없는 READY 의 금액이 다르면 AMOUNT_CHANGED 로 버리고 새 READY — 201")
        void replacesWhenAmountChanged() {
            Payment stale = Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(100_000), ORDER_NAME);
            Payment created = PaymentFixtures.ready(PAYMENT_ID + 1, ORDER_ID);
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.of(stale));
            given(paymentProcessor.replace(stale, PaymentAbortReason.AMOUNT_CHANGED, MEMBER_ID, ORDER)).willReturn(created);

            PrepareResult result = prepare();

            assertThat(result.created()).isTrue();
            assertThat(result.payment().paymentId()).isEqualTo(Long.toString(PAYMENT_ID + 1));
        }

        @Test
        @DisplayName("결제키 없는 READY 의 주문명만 다르면 REPREPARED 로 버리고 새 READY")
        void replacesWhenNameChanged() {
            Payment stale = Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(129_000), "옛 주문명");
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.of(stale));
            given(paymentProcessor.replace(stale, PaymentAbortReason.REPREPARED, MEMBER_ID, ORDER)).willReturn(PaymentFixtures.ready(PAYMENT_ID + 1, ORDER_ID));

            assertThat(prepare().created()).isTrue();
        }

        @Test
        @DisplayName("결제키 있는 되돌린 READY 의 금액이 다르면 409 PAYMENT_012 — 조회 없이 버리지 않는다")
        void revertedReadyWithOtherAmountConflicts() {
            Payment reverted = Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(100_000), ORDER_NAME)
                .startApproval(PAYMENT_KEY, APPROVAL_KEY, NOW).revertToReady();
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.of(reverted));

            assertError(this::prepare, PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
            verify(paymentProcessor, never()).replace(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("결제키 있는 되돌린 READY 는 금액이 같으면 그대로 200 이다")
        void revertedReadyWithSameAmountIsReturned() {
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.of(PaymentFixtures.reverted()));

            assertThat(prepare().created()).isFalse();
        }

        @Test
        @DisplayName("IN_PROGRESS · UNKNOWN · DONE 은 금액이 달라도 그 시도를 200 으로 돌려준다")
        void returnsAttemptInFlight() {
            for (Payment payment : new Payment[] {PaymentFixtures.inProgress(), PaymentFixtures.unknown(), PaymentFixtures.done()}) {
                given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.of(payment));

                PrepareResult result = prepare();

                assertThat(result.created()).isFalse();
                assertThat(result.payment().status().code()).isEqualTo(payment.status().name());
            }
        }

        @Test
        @DisplayName("생성이 활성 슬롯과 겹치면 새 트랜잭션에서 다시 읽어 그대로 돌려줄 수 있으면 200")
        void activeSlotConflictRereads() {
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.empty(), Optional.of(PaymentFixtures.ready()));
            given(paymentProcessor.prepare(MEMBER_ID, ORDER)).willThrow(new PaymentPersistenceConflictException(Kind.ACTIVE_SLOT, PAYMENT_ID, ORDER_ID));

            assertThat(prepare().created()).isFalse();
        }

        @Test
        @DisplayName("다시 읽어도 답할 수 없으면 409 PAYMENT_012")
        void conflictWithoutAnswer() {
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.empty(), Optional.empty());
            given(paymentProcessor.prepare(MEMBER_ID, ORDER)).willThrow(new PaymentPersistenceConflictException(Kind.ACTIVE_SLOT, PAYMENT_ID, ORDER_ID));

            assertError(this::prepare, PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        }

        @Test
        @DisplayName("주문 판정 오류(없음 · 상태 · 통화 · 기한)는 그대로 올리고 결제 시도를 보지 않는다")
        void orderRejectionStops() {
            given(orderSummaryProcessor.getPayableOrder(ORDER_ID, MEMBER_ID)).willThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUE_TOO_CLOSE));

            assertError(this::prepare, PaymentErrorCode.PAYMENT_DUE_TOO_CLOSE);
            verifyNoInteractions(paymentProcessor);
        }

        @Test
        @DisplayName("활성 시도가 다른 회원의 것이면(데이터 이상) 건드리지 않고 409 PAYMENT_012")
        void otherMembersAttempt() {
            given(paymentProcessor.findActive(ORDER_ID)).willReturn(Optional.of(Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID + 1, krw(129_000), ORDER_NAME)));

            assertError(this::prepare, PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
    }

    @Nested
    @DisplayName("결제 승인")
    class Confirm {

        private PaymentResponse confirm(UUID key, PaymentConfirmCommand command) {
            return facade.confirm(MEMBER_ID, key, command);
        }

        private void givenCurrent(Payment payment) {
            given(paymentProcessor.getOwnedByPgOrderId(MEMBER_ID, PG_ORDER_ID)).willReturn(payment);
        }

        private Payment givenStarted() {
            Payment ready = PaymentFixtures.ready();
            Payment inProgress = ready.startApproval(PAYMENT_KEY, APPROVAL_KEY, NOW);
            givenCurrent(ready);
            given(paymentProcessor.startApproval(ready, PAYMENT_KEY, APPROVAL_KEY, NOW)).willReturn(inProgress);
            return inProgress;
        }

        private void assertNoApproval() {
            verify(paymentProcessor, never()).startApproval(any(), anyString(), anyString(), any());
            verifyNoInteractions(paymentGatewayProcessor);
        }

        @Test
        @DisplayName("READY → 승인 시작(고객 키 기록) → PG(트랜잭션 밖) → DONE 기록 → 200")
        void approves() {
            Payment inProgress = givenStarted();
            PgApprovalOutcome approved = new PgApprovalOutcome.Approved(approval(krw(129_000)));
            given(paymentGatewayProcessor.approve(inProgress)).willReturn(approved);
            given(paymentProcessor.recordApprovalResult(inProgress, approved)).willReturn(inProgress.approve(approval(krw(129_000))));

            PaymentResponse response = confirm(KEY, CONFIRM);

            assertThat(response.status().code()).isEqualTo("DONE");
            assertThat(response.failure()).isNull();
        }

        @Test
        @DisplayName("Rejected → FAILED 200(실패 사유), Unknown → UNKNOWN 200")
        void rejectedAndUnknown() {
            Payment inProgress = givenStarted();
            PgApprovalOutcome rejected = new PgApprovalOutcome.Rejected("REJECT_CARD_COMPANY", "카드사 거절");
            given(paymentGatewayProcessor.approve(inProgress)).willReturn(rejected);
            given(paymentProcessor.recordApprovalResult(inProgress, rejected)).willReturn(inProgress.reject("REJECT_CARD_COMPANY", "카드사 거절"));

            PaymentResponse failed = confirm(KEY, CONFIRM);
            assertThat(failed.status().code()).isEqualTo("FAILED");
            assertThat(failed.failure().code()).isEqualTo("REJECT_CARD_COMPANY");

            PgApprovalOutcome unknown = new PgApprovalOutcome.Unknown(PaymentUnknownReason.TIMEOUT);
            given(paymentGatewayProcessor.approve(inProgress)).willReturn(unknown);
            given(paymentProcessor.recordApprovalResult(inProgress, unknown)).willReturn(inProgress.markUnknown(PaymentUnknownReason.TIMEOUT));

            assertThat(confirm(KEY, CONFIRM).status().code()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("NotSent → READY 로 되돌리고 503 PAYMENT_010 + Retry-After 5초")
        void notSentIsRetryable() {
            Payment inProgress = givenStarted();
            PgApprovalOutcome notSent = new PgApprovalOutcome.NotSent("CIRCUIT_OPEN");
            given(paymentGatewayProcessor.approve(inProgress)).willReturn(notSent);
            given(paymentProcessor.recordApprovalResult(inProgress, notSent)).willReturn(inProgress.revertToReady());

            assertThatThrownBy(() -> confirm(KEY, CONFIRM)).isInstanceOfSatisfying(PaymentRetryLaterException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.GATEWAY_UNAVAILABLE);
                assertThat(exception.getRetryAfter()).isEqualTo(Duration.ofSeconds(5));
            });
        }

        @Test
        @DisplayName("되돌린 READY 는 새 고객 키로 와도 같은 결제키면 다시 승인을 시작한다")
        void revertedReadyRestartsWithNewKey() {
            Payment reverted = PaymentFixtures.reverted();
            Payment restarted = reverted.startApproval(PAYMENT_KEY, OTHER_KEY.toString(), NOW);
            givenCurrent(reverted);
            given(paymentProcessor.startApproval(reverted, PAYMENT_KEY, OTHER_KEY.toString(), NOW)).willReturn(restarted);
            PgApprovalOutcome approved = new PgApprovalOutcome.Approved(approval(krw(129_000)));
            given(paymentGatewayProcessor.approve(restarted)).willReturn(approved);
            given(paymentProcessor.recordApprovalResult(restarted, approved)).willReturn(restarted.approve(approval(krw(129_000))));

            assertThat(confirm(OTHER_KEY, CONFIRM).status().code()).isEqualTo("DONE");
        }

        @Test
        @DisplayName("같은 키 · 같은 결제키의 재요청(DONE · UNKNOWN · FAILED)은 현재 상태 그대로 — 승인 시작 · PG · 저장이 없다")
        void sameKeyReplayReturnsCurrent() {
            for (Payment payment : new Payment[] {PaymentFixtures.done(), PaymentFixtures.unknown(), PaymentFixtures.failed()}) {
                givenCurrent(payment);
                given(paymentProcessor.findByApprovalKey(MEMBER_ID, APPROVAL_KEY)).willReturn(Optional.of(payment));

                assertThat(confirm(KEY, CONFIRM).status().code()).isEqualTo(payment.status().name());
            }
            assertNoApproval();
            verify(paymentProcessor, never()).recordApprovalResult(any(), any());
        }

        @Test
        @DisplayName("같은 키인데 결제키가 다르면 409 PAYMENT_014")
        void sameKeyOtherPaymentKey() {
            givenCurrent(PaymentFixtures.done());

            assertError(() -> confirm(KEY, new PaymentConfirmCommand("tgen_other000000000001", PG_ORDER_ID, 129_000, "KRW")), PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
            assertNoApproval();
        }

        @Test
        @DisplayName("이 키로 다른 결제 시도의 승인을 이미 시작했으면 409 PAYMENT_014")
        void keyReusedForOtherPayment() {
            givenCurrent(PaymentFixtures.ready());
            given(paymentProcessor.findByApprovalKey(MEMBER_ID, APPROVAL_KEY)).willReturn(Optional.of(PaymentFixtures.ready(PAYMENT_ID + 5, ORDER_ID + 5)
                .startApproval("tgen_other000000000001", APPROVAL_KEY, NOW)));

            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
            assertNoApproval();
        }

        @Test
        @DisplayName("다른 키 — DONE · UNKNOWN 에 같은 결제키면 현재 상태 200, 다른 결제키면 400 PAYMENT_009")
        void otherKeyOnSettled() {
            PaymentConfirmCommand otherPaymentKey = new PaymentConfirmCommand("tgen_other000000000001", PG_ORDER_ID, 129_000, "KRW");
            for (Payment payment : new Payment[] {PaymentFixtures.done(), PaymentFixtures.unknown()}) {
                givenCurrent(payment);

                assertThat(confirm(OTHER_KEY, CONFIRM).status().code()).isEqualTo(payment.status().name());
                assertError(() -> confirm(OTHER_KEY, otherPaymentKey), PaymentErrorCode.PAYMENT_KEY_MISMATCH);
            }
            assertNoApproval();
        }

        @Test
        @DisplayName("다른 키로 온 FAILED · ABORTED · LATE_APPROVED 는 409 PAYMENT_006 — 결제 준비부터 다시")
        void closedAttempts() {
            for (Payment payment : new Payment[] {PaymentFixtures.failed(), PaymentFixtures.aborted(), PaymentFixtures.lateApproved()}) {
                givenCurrent(payment);

                assertError(() -> confirm(OTHER_KEY, CONFIRM), PaymentErrorCode.PAYMENT_ATTEMPT_CLOSED);
            }
            assertNoApproval();
        }

        @Test
        @DisplayName("IN_PROGRESS 는 같은 키든 아니든 409 PAYMENT_007")
        void inProgressConflicts() {
            givenCurrent(PaymentFixtures.inProgress());

            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.APPROVAL_IN_PROGRESS);
            assertError(() -> confirm(OTHER_KEY, CONFIRM), PaymentErrorCode.APPROVAL_IN_PROGRESS);
            assertNoApproval();
        }

        @Test
        @DisplayName("금액 · 통화가 결제 시도와 다르면 PG 를 부르지 않고 400 PAYMENT_008")
        void amountMismatchSkipsGateway() {
            givenCurrent(PaymentFixtures.ready());

            assertError(() -> confirm(KEY, new PaymentConfirmCommand(PAYMENT_KEY, PG_ORDER_ID, 1_000, "KRW")), PaymentErrorCode.AMOUNT_MISMATCH);
            assertError(() -> confirm(KEY, new PaymentConfirmCommand(PAYMENT_KEY, PG_ORDER_ID, 129_000, "USD")), PaymentErrorCode.AMOUNT_MISMATCH);
            assertError(() -> confirm(KEY, new PaymentConfirmCommand(PAYMENT_KEY, PG_ORDER_ID, 129_000, "JPY")), PaymentErrorCode.AMOUNT_MISMATCH);
            assertNoApproval();
        }

        @Test
        @DisplayName("승인 시작 — 되돌린 READY 의 다른 결제키(도메인 불일치) · 다른 시도의 결제키(유니크 키)는 400 PAYMENT_009, 키 유니크 충돌은 409 PAYMENT_014")
        void startApprovalRejections() {
            Payment ready = PaymentFixtures.ready();
            givenCurrent(ready);

            willThrow(new PaymentApprovalMismatchException(PAYMENT_ID, PaymentApprovalMismatchException.Field.PAYMENT_KEY)).given(paymentProcessor).startApproval(any(), anyString(), anyString(), any());
            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.PAYMENT_KEY_MISMATCH);

            willThrow(new PaymentPersistenceConflictException(Kind.PAYMENT_KEY, PAYMENT_ID, ORDER_ID)).given(paymentProcessor).startApproval(any(), anyString(), anyString(), any());
            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.PAYMENT_KEY_MISMATCH);

            willThrow(new PaymentPersistenceConflictException(Kind.APPROVAL_KEY, PAYMENT_ID, ORDER_ID)).given(paymentProcessor).startApproval(any(), anyString(), anyString(), any());
            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
            verifyNoInteractions(paymentGatewayProcessor);
        }

        @Test
        @DisplayName("승인 시작이 버전에서 지면 새 트랜잭션에서 다시 읽는다 — IN_PROGRESS 면 409 PAYMENT_007, 같은 키로 끝났으면 그 상태 200")
        void startApprovalVersionConflictRereads() {
            Payment ready = PaymentFixtures.ready();
            givenCurrent(ready);
            willThrow(new PaymentPersistenceConflictException(Kind.VERSION, PAYMENT_ID, ORDER_ID)).given(paymentProcessor).startApproval(any(), anyString(), anyString(), any());

            given(paymentProcessor.getOwned(PAYMENT_ID, MEMBER_ID)).willReturn(PaymentFixtures.inProgress());
            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.APPROVAL_IN_PROGRESS);

            given(paymentProcessor.getOwned(PAYMENT_ID, MEMBER_ID)).willReturn(PaymentFixtures.done());
            assertThat(confirm(KEY, CONFIRM).status().code()).isEqualTo("DONE");
            verifyNoInteractions(paymentGatewayProcessor);
        }

        @Test
        @DisplayName("승인 시작이 버전에서 지고 다시 읽은 상태가 READY(다른 요청이 보내지 못해 되돌림)면 409 PAYMENT_012 — PG 를 부르지 않는다")
        void startApprovalVersionConflictToReady() {
            givenCurrent(PaymentFixtures.ready());
            willThrow(new PaymentPersistenceConflictException(Kind.VERSION, PAYMENT_ID, ORDER_ID)).given(paymentProcessor).startApproval(any(), anyString(), anyString(), any());
            given(paymentProcessor.getOwned(PAYMENT_ID, MEMBER_ID)).willReturn(PaymentFixtures.reverted());

            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
            verifyNoInteractions(paymentGatewayProcessor);
        }

        @Test
        @DisplayName("같은 키 · 같은 결제키인데 금액 · 통화가 다른 재요청(DONE · UNKNOWN)은 409 PAYMENT_014 — 같은 요청의 재전송이 아니다")
        void sameKeyReplayWithOtherAmount() {
            for (Payment payment : new Payment[] {PaymentFixtures.done(), PaymentFixtures.unknown()}) {
                givenCurrent(payment);

                assertError(() -> confirm(KEY, new PaymentConfirmCommand(PAYMENT_KEY, PG_ORDER_ID, 1_000, "KRW")), PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
                assertError(() -> confirm(KEY, new PaymentConfirmCommand(PAYMENT_KEY, PG_ORDER_ID, 129_000, "USD")), PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            assertNoApproval();
        }

        @Test
        @DisplayName("승인 직전 주문 재확인 — 없음 404 · 결제 대기 아님 409 · 기한 경과 409 · 주문 장애 503 은 그대로, PG · 저장 없음")
        void orderRecheckRejections() {
            givenCurrent(PaymentFixtures.ready());
            for (PaymentErrorCode errorCode : new PaymentErrorCode[] {
                PaymentErrorCode.NOT_FOUND_ORDER, PaymentErrorCode.ORDER_NOT_PAYABLE, PaymentErrorCode.PAYMENT_DUE_TOO_CLOSE, PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE}) {
                willThrow(new PaymentException(errorCode)).given(orderSummaryProcessor).getConfirmableOrder(ORDER_ID, MEMBER_ID);

                assertError(() -> confirm(KEY, CONFIRM), errorCode);
            }
            assertNoApproval();
            verify(paymentProcessor, never()).recordApprovalResult(any(), any());
        }

        @Test
        @DisplayName("주문 요약 금액이 결제 시도와 다르면(결함 신호) 409 PAYMENT_012 — PG · 저장 없음")
        void orderRecheckAmountDiffers() {
            givenCurrent(PaymentFixtures.ready());
            given(orderSummaryProcessor.getConfirmableOrder(ORDER_ID, MEMBER_ID)).willReturn(new PayableOrder(ORDER_ID, krw(100_000), ORDER_NAME, DUE_AT));

            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
            assertNoApproval();
        }

        @Test
        @DisplayName("READY 가 아니어서 상태 분기에서 끝나는 요청은 order-service 를 부르지 않는다")
        void settledAttemptSkipsOrderRecheck() {
            givenCurrent(PaymentFixtures.done());

            confirm(KEY, CONFIRM);

            verify(orderSummaryProcessor, never()).getConfirmableOrder(anyLong(), anyLong());
        }

        @Test
        @DisplayName("금액 불일치(400)는 주문 재확인보다 먼저다 — order 를 부르지 않는다")
        void amountMismatchBeforeOrderRecheck() {
            givenCurrent(PaymentFixtures.ready());

            assertError(() -> confirm(KEY, new PaymentConfirmCommand(PAYMENT_KEY, PG_ORDER_ID, 1_000, "KRW")), PaymentErrorCode.AMOUNT_MISMATCH);
            verify(orderSummaryProcessor, never()).getConfirmableOrder(anyLong(), anyLong());
        }

        @Test
        @DisplayName("결과 기록이 버전에서 지면(재조회 · 웹훅이 먼저) 다시 읽은 현재 상태 200")
        void recordVersionConflictRereads() {
            Payment inProgress = givenStarted();
            PgApprovalOutcome approved = new PgApprovalOutcome.Approved(approval(krw(129_000)));
            given(paymentGatewayProcessor.approve(inProgress)).willReturn(approved);
            given(paymentProcessor.recordApprovalResult(inProgress, approved)).willThrow(new PaymentPersistenceConflictException(Kind.VERSION, PAYMENT_ID, ORDER_ID));
            given(paymentProcessor.getOwned(PAYMENT_ID, MEMBER_ID)).willReturn(PaymentFixtures.done());

            assertThat(confirm(KEY, CONFIRM).status().code()).isEqualTo("DONE");
        }

        @Test
        @DisplayName("결과 기록이 그 밖의 이유로 실패하면 500 PAYMENT_013 — 원인은 싣되 응답 문구는 코드 문구다")
        void recordFailure() {
            Payment inProgress = givenStarted();
            PgApprovalOutcome approved = new PgApprovalOutcome.Approved(approval(krw(129_000)));
            given(paymentGatewayProcessor.approve(inProgress)).willReturn(approved);
            given(paymentProcessor.recordApprovalResult(inProgress, approved)).willThrow(new DataAccessResourceFailureException("db down"));

            assertThatThrownBy(() -> confirm(KEY, CONFIRM)).isInstanceOfSatisfying(PaymentException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.APPROVAL_RECORD_FAILED);
                assertThat(exception.getMessage()).doesNotContain("db down");
            });
        }

        @Test
        @DisplayName("결제 시도가 없거나 남의 것이면 404 PAYMENT_001 그대로")
        void notOwned() {
            given(paymentProcessor.getOwnedByPgOrderId(eq(MEMBER_ID), anyString())).willThrow(new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));

            assertError(() -> confirm(KEY, CONFIRM), PaymentErrorCode.NOT_FOUND_PAYMENT);
            assertNoApproval();
        }
    }

    @Test
    @DisplayName("결제 게이트가 닫혀 있으면 준비 · 승인 모두 503 PAYMENT_015 — order · DB · PG 를 부르지 않는다. 상태 조회는 막지 않는다")
    void gateClosed() {
        PaymentWebFacade closed = new PaymentWebFacade(orderSummaryProcessor, paymentProcessor, paymentGatewayProcessor, new PaymentWebPresenter(),
            new PaymentGateProperties(false), Clock.fixed(NOW, ZoneOffset.UTC));

        assertError(() -> closed.prepare(MEMBER_ID, new PaymentPrepareCommand(ORDER_ID)), PaymentErrorCode.PAYMENTS_DISABLED);
        assertError(() -> closed.confirm(MEMBER_ID, KEY, CONFIRM), PaymentErrorCode.PAYMENTS_DISABLED);
        verifyNoInteractions(orderSummaryProcessor, paymentProcessor, paymentGatewayProcessor);

        given(paymentProcessor.getOwned(PAYMENT_ID, MEMBER_ID)).willReturn(PaymentFixtures.done());
        assertThat(closed.getPayment(MEMBER_ID, PAYMENT_ID).status().code()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("상태 조회는 소유자 확인을 Processor 에 맡기고 응답으로 바꾼다")
    void getsPayment() {
        given(paymentProcessor.getOwned(PAYMENT_ID, MEMBER_ID)).willReturn(PaymentFixtures.done());

        PaymentResponse response = facade.getPayment(MEMBER_ID, PAYMENT_ID);

        assertThat(response.paymentId()).isEqualTo(Long.toString(PAYMENT_ID));
        assertThat(response.status().code()).isEqualTo(PaymentStatus.DONE.name());
    }
}
