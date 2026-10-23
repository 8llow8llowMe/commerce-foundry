package com.commercefoundry.domainlayer.payment.domain.model;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_NAME;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.STARTED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.approval;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException.Field;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException.Action;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-21T02:00:00Z");
    private static final PaymentInquiry.Failed NOT_APPROVED = new PaymentInquiry.Failed("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다.");

    /**
     * 시작 상태 — 전이 표의 행. 결제키가 없는 READY 와 되돌린 READY(결제키 있음)는 허용 전이가 달라 따로 둔다.
     * 취소 상태 · 입금 대기는 들어오는 전이가 없어 만들 수 없다.
     */
    private static final Map<String, Supplier<Payment>> STATES = new LinkedHashMap<>();
    /** 전이 메서드 — 전이 표의 열. */
    private static final Map<Action, UnaryOperator<Payment>> ACTIONS = new LinkedHashMap<>();
    /** 허용 전이 표 — 시작 상태 → 허용된 동작. 나머지 칸은 전부 거절이다. */
    private static final Map<String, Set<Action>> ALLOWED = new LinkedHashMap<>();

    static {
        STATES.put("READY", PaymentFixtures::ready);
        STATES.put("READY(되돌림)", PaymentFixtures::reverted);
        STATES.put("IN_PROGRESS", PaymentFixtures::inProgress);
        STATES.put("DONE", PaymentFixtures::done);
        STATES.put("FAILED", PaymentFixtures::failed);
        STATES.put("UNKNOWN", PaymentFixtures::unknown);
        STATES.put("ABORTED", PaymentFixtures::aborted);
        STATES.put("LATE_APPROVED", PaymentFixtures::lateApproved);

        ACTIONS.put(Action.START_APPROVAL, payment -> payment.startApproval(PAYMENT_KEY, NOW));
        ACTIONS.put(Action.ABORT, payment -> payment.abort(PaymentAbortReason.AMOUNT_CHANGED));
        ACTIONS.put(Action.ABORT_AFTER_INQUIRY, payment -> payment.abortAfterInquiry(NOT_APPROVED, PaymentAbortReason.ORDER_EXPIRED));
        ACTIONS.put(Action.APPROVE, payment -> payment.approve(approval(krw(129_000))));
        ACTIONS.put(Action.REJECT, payment -> payment.reject("REJECT_CARD_COMPANY", null));
        ACTIONS.put(Action.MARK_UNKNOWN, payment -> payment.markUnknown(PaymentUnknownReason.SERVER_ERROR));
        ACTIONS.put(Action.REVERT_TO_READY, Payment::revertToReady);
        ACTIONS.put(Action.RESOLVE, payment -> payment.resolve(NOT_APPROVED));
        ACTIONS.put(Action.FLAG_FOR_MANUAL_REVIEW, payment -> payment.flagForManualReview(PaymentUnknownReason.MANUAL_REVIEW));
        ACTIONS.put(Action.MARK_ORDER_SYNCED, Payment::markOrderSynced);
        ACTIONS.put(Action.MARK_ORDER_SYNC_FAILED, payment -> payment.markOrderSyncFailed("ORDER_030"));
        ACTIONS.put(Action.RECORD_LATE_APPROVAL, payment -> payment.recordLateApproval(approval(krw(129_000))));

        ALLOWED.put("READY", Set.of(Action.START_APPROVAL, Action.ABORT));
        ALLOWED.put("READY(되돌림)", Set.of(Action.START_APPROVAL, Action.ABORT_AFTER_INQUIRY));
        ALLOWED.put("IN_PROGRESS", Set.of(Action.APPROVE, Action.REJECT, Action.MARK_UNKNOWN, Action.REVERT_TO_READY));
        ALLOWED.put("DONE", Set.of(Action.MARK_ORDER_SYNCED, Action.MARK_ORDER_SYNC_FAILED));
        ALLOWED.put("FAILED", Set.of(Action.RECORD_LATE_APPROVAL));
        ALLOWED.put("UNKNOWN", Set.of(Action.RESOLVE, Action.FLAG_FOR_MANUAL_REVIEW));
        ALLOWED.put("ABORTED", Set.of(Action.RECORD_LATE_APPROVAL));
        ALLOWED.put("LATE_APPROVED", Set.of());
    }

    static Stream<Arguments> forbiddenTransitions() {
        List<Arguments> forbidden = new ArrayList<>();
        STATES.forEach((label, state) -> ACTIONS.forEach((action, transition) -> {
            if (!ALLOWED.get(label).contains(action)) {
                forbidden.add(Arguments.of(label, action, state, transition));
            }
        }));
        return forbidden.stream();
    }

    static Stream<Arguments> allowedTransitions() {
        List<Arguments> allowed = new ArrayList<>();
        ALLOWED.forEach((label, actions) -> actions.forEach(action -> allowed.add(Arguments.of(label, action))));
        return allowed.stream();
    }

    @ParameterizedTest(name = "{0} 에서 {1} 은 거절된다")
    @MethodSource("forbiddenTransitions")
    @DisplayName("전이 표 밖은 현재 상태 · 시도한 동작을 실은 PaymentTransitionException 이다")
    void forbiddenTransitionIsRejected(String label, Action action, Supplier<Payment> state, UnaryOperator<Payment> transition) {
        Payment payment = state.get();

        assertThatThrownBy(() -> transition.apply(payment))
            .isInstanceOfSatisfying(PaymentTransitionException.class, exception -> {
                assertThat(exception.getCurrentStatus()).isEqualTo(payment.status());
                assertThat(exception.getAttemptedAction()).isEqualTo(action);
            });
    }

    @ParameterizedTest(name = "{0} 에서 {1}")
    @MethodSource("allowedTransitions")
    @DisplayName("전이 표 안의 칸은 모두 통과한다 — 표와 구현이 같다")
    void allowedTransitionPasses(String label, Action action) {
        Payment payment = STATES.get(label).get();

        Payment result = ACTIONS.get(action).apply(payment);

        assertThat(result).isNotNull();
    }

    @Nested
    @DisplayName("ready")
    class Ready {

        @Test
        @DisplayName("READY 로 만들고 PG 주문번호는 아이디 문자열, 활성 슬롯은 주문 아이디, 버전은 없다")
        void createsReady() {
            Payment payment = Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(129_000), ORDER_NAME);

            assertThat(payment.status()).isEqualTo(PaymentStatus.READY);
            assertThat(payment.pgOrderId()).isEqualTo(Long.toString(PAYMENT_ID));
            assertThat(payment.gateway()).isEqualTo(PaymentGateway.TOSS);
            assertThat(payment.amount()).isEqualTo(krw(129_000));
            assertThat(payment.activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(payment.paymentKey()).isNull();
            assertThat(payment.orderSynced()).isFalse();
            assertThat(payment.version()).isNull();
        }

        @Test
        @DisplayName("금액 0 원은 거절한다")
        void zeroAmountIsRejected() {
            assertThatThrownBy(() -> Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(0), ORDER_NAME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 보다");
        }

        @Test
        @DisplayName("음수 금액은 Money 부터 만들 수 없다")
        void negativeAmountIsRejected() {
            assertThatThrownBy(() -> Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(-1), ORDER_NAME))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("지금은 KRW 만 받는다 — USD 는 해외 결제 이슈에서 연다")
        void usdIsRejected() {
            assertThatThrownBy(() -> Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, Money.of(8_950, CurrencyCode.USD), ORDER_NAME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KRW");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("빈 주문명은 거절한다")
        void blankOrderNameIsRejected(String orderName) {
            assertThatThrownBy(() -> Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(1_000), orderName))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("주문명은 100자까지 받고 101자는 거절한다")
        void orderNameLength() {
            assertThat(Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(1_000), "가".repeat(100)).orderName()).hasSize(100);
            assertThatThrownBy(() -> Payment.ready(PAYMENT_ID, ORDER_ID, MEMBER_ID, krw(1_000), "가".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderName");
        }

        @Test
        @DisplayName("아이디가 6자리 미만이면 PG 주문번호 규칙(6자 이상)에 걸린다")
        void shortIdIsRejected() {
            assertThat(Payment.ready(100_000L, ORDER_ID, MEMBER_ID, krw(1_000), ORDER_NAME).pgOrderId()).isEqualTo("100000");
            assertThatThrownBy(() -> Payment.ready(99_999L, ORDER_ID, MEMBER_ID, krw(1_000), ORDER_NAME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PG 주문번호");
        }
    }

    @Nested
    @DisplayName("PG 주문번호 규칙 (6~64자, 영문 · 숫자 · - · _)")
    class PgOrderId {

        private Payment readyWith(String pgOrderId) {
            return new Payment(
                PAYMENT_ID, ORDER_ID, MEMBER_ID, pgOrderId, PaymentGateway.TOSS, krw(1_000), ORDER_NAME, PaymentStatus.READY,
                null, null, null, null, null, null, null, null, null, false, null, null, null
            );
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"abc-_9", "ORDER_2026-10-21_0001", "a234567890123456789012345678901234567890123456789012345678901234"})
        @DisplayName("규칙 안의 번호는 받는다")
        void acceptsValid(String pgOrderId) {
            assertThat(readyWith(pgOrderId).pgOrderId()).isEqualTo(pgOrderId);
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"abc12", "a2345678901234567890123456789012345678901234567890123456789012345", "order!0001", "주문번호0001", "order 0001"})
        @DisplayName("짧거나(5자) · 길거나(65자) · 허용 밖 문자가 있으면 거절한다 — 메시지에 입력값을 싣지 않는다")
        void rejectsInvalid(String pgOrderId) {
            assertThatThrownBy(() -> readyWith(pgOrderId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PG 주문번호")
                .hasMessageNotContaining(pgOrderId);
        }
    }

    @Nested
    @DisplayName("활성 슬롯 (activeOrderId)")
    class ActiveOrderId {

        @Test
        @DisplayName("READY · IN_PROGRESS · UNKNOWN · DONE 은 주문 아이디, FAILED · ABORTED · LATE_APPROVED 는 null 이다")
        void activeOrderIdByStatus() {
            assertThat(PaymentFixtures.ready().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.reverted().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.inProgress().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.unknown().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.done().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.failed().activeOrderId()).isNull();
            assertThat(PaymentFixtures.aborted().activeOrderId()).isNull();
            assertThat(PaymentFixtures.lateApproved().activeOrderId()).isNull();
        }

        @Test
        @DisplayName("취소 상태는 승인된 결제와 같이 활성으로 본다 (닿지 않는 상태 — 취소 이슈에서 다시 정한다)")
        void canceledStatusesKeepSlot() {
            assertThat(PaymentStatus.PARTIAL_CANCELED.releasesActiveSlot()).isFalse();
            assertThat(PaymentStatus.CANCELED.releasesActiveSlot()).isFalse();
            assertThat(PaymentStatus.CANCELED.hasApproval()).isTrue();
            assertThat(PaymentStatus.LATE_APPROVED.hasApproval()).isTrue();
            assertThat(PaymentStatus.LATE_APPROVED.reportsToOrder()).isFalse();
        }
    }

    @Nested
    @DisplayName("승인 시작 · 되돌림 · 중단")
    class StartApproval {

        @Test
        @DisplayName("READY → IN_PROGRESS 는 결제키와 승인 시작 시각을 남기고 버전을 그대로 둔다")
        void startsApproval() {
            Payment ready = withVersion(PaymentFixtures.ready(), 3L);

            Payment inProgress = ready.startApproval(PAYMENT_KEY, STARTED_AT);

            assertThat(inProgress.status()).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(inProgress.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(inProgress.approvalStartedAt()).isEqualTo(STARTED_AT);
            assertThat(inProgress.version()).isEqualTo(3L);
            assertThat(ready.status()).as("원래 인스턴스는 바뀌지 않는다").isEqualTo(PaymentStatus.READY);
        }

        @ParameterizedTest(name = "{0}자")
        @ValueSource(ints = {0, 201})
        @DisplayName("빈 결제키 · 200자를 넘는 결제키는 거절한다")
        void invalidPaymentKeyIsRejected(int length) {
            assertThatThrownBy(() -> PaymentFixtures.ready().startApproval("k".repeat(length), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("결제키 200자는 받는다")
        void maxPaymentKeyIsAccepted() {
            assertThat(PaymentFixtures.ready().startApproval("k".repeat(200), NOW).paymentKey()).hasSize(200);
        }

        @Test
        @DisplayName("IN_PROGRESS → READY 는 결제키 · 승인 시작 시각을 흔적으로 남기고 활성 슬롯을 지킨다")
        void revertKeepsTrace() {
            Payment reverted = PaymentFixtures.inProgress().revertToReady();

            assertThat(reverted.status()).isEqualTo(PaymentStatus.READY);
            assertThat(reverted.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(reverted.approvalStartedAt()).isEqualTo(STARTED_AT);
            assertThat(reverted.activeOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("되돌린 READY 는 같은 결제키로만 다시 시작한다 — 승인 시작 시각은 새로 남긴다")
        void revertedRestartsWithSameKey() {
            Payment restarted = PaymentFixtures.reverted().startApproval(PAYMENT_KEY, NOW);

            assertThat(restarted.status()).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(restarted.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(restarted.approvalStartedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("되돌린 READY 에 다른 결제키가 오면 PAYMENT_KEY 불일치다 — 이전 키가 승인됐을 수 있어 바꾸지 않는다")
        void revertedRejectsOtherKey() {
            Payment reverted = PaymentFixtures.reverted();

            assertMismatch(() -> reverted.startApproval("tgen_reauthenticated0001", NOW), Field.PAYMENT_KEY);
        }

        @Test
        @DisplayName("READY(결제키 없음) → ABORTED 는 사유를 남기고 활성 슬롯을 놓는다")
        void abortReleasesSlot() {
            Payment aborted = PaymentFixtures.ready().abort(PaymentAbortReason.ORDER_EXPIRED);

            assertThat(aborted.status()).isEqualTo(PaymentStatus.ABORTED);
            assertThat(aborted.abortReason()).isEqualTo(PaymentAbortReason.ORDER_EXPIRED);
            assertThat(aborted.activeOrderId()).isNull();
        }

        @Test
        @DisplayName("되돌린 READY 는 조회 없이 버릴 수 없다 — 이전 결제키가 승인됐으면 새 시도와 이중 결제가 된다")
        void revertedCannotAbortWithoutInquiry() {
            Payment reverted = PaymentFixtures.reverted();

            assertThatThrownBy(() -> reverted.abort(PaymentAbortReason.REPREPARED))
                .isInstanceOfSatisfying(PaymentTransitionException.class, exception -> {
                    assertThat(exception.getCurrentStatus()).isEqualTo(PaymentStatus.READY);
                    assertThat(exception.getAttemptedAction()).isEqualTo(Action.ABORT);
                });
        }

        @Test
        @DisplayName("되돌린 READY 는 PG 조회로 미승인을 확인한 뒤 버린다 — 결제키 · 승인 시작 시각은 흔적으로 남는다")
        void revertedAbortsAfterInquiry() {
            Payment aborted = PaymentFixtures.reverted().abortAfterInquiry(NOT_APPROVED, PaymentAbortReason.REPREPARED);

            assertThat(aborted.status()).isEqualTo(PaymentStatus.ABORTED);
            assertThat(aborted.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(aborted.approvalStartedAt()).isEqualTo(STARTED_AT);
            assertThat(aborted.failureCode()).as("조회 결과의 거절 코드는 FAILED 칸이라 남기지 않는다").isNull();
            assertThat(aborted.activeOrderId()).isNull();
        }
    }

    @Nested
    @DisplayName("승인 · 거절 · 미확정")
    class Approval {

        @Test
        @DisplayName("IN_PROGRESS → DONE 은 결제수단 · 간편결제 제공자 · PG 상태 · 승인 시각을 남기고 주문 반영 전이다")
        void approves() {
            Payment done = PaymentFixtures.inProgress().approve(approval(krw(129_000)));

            assertThat(done.status()).isEqualTo(PaymentStatus.DONE);
            assertThat(done.method()).isEqualTo(PaymentMethod.EASY_PAY);
            assertThat(done.easyPayProvider()).isEqualTo("카카오페이");
            assertThat(done.pgStatus()).isEqualTo("DONE");
            assertThat(done.approvedAt()).isEqualTo(APPROVED_AT);
            assertThat(done.approvalStartedAt()).isEqualTo(STARTED_AT);
            assertThat(done.orderSynced()).isFalse();
            assertThat(done.activeOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("승인 금액이 결제 금액과 다르면 AMOUNT 불일치다 — 값은 싣지 않는다")
        void amountMismatchIsRejected() {
            Payment inProgress = PaymentFixtures.inProgress();

            assertMismatch(() -> inProgress.approve(approval(krw(1_000))), Field.AMOUNT);
        }

        @Test
        @DisplayName("승인 결과의 결제키가 다르면 PAYMENT_KEY 불일치다 — 결제키는 메시지에 싣지 않는다")
        void paymentKeyMismatchIsRejected() {
            Payment inProgress = PaymentFixtures.inProgress();

            assertMismatch(() -> inProgress.approve(approval("tgen_other_payment_key", krw(129_000))), Field.PAYMENT_KEY);
            assertThatThrownBy(() -> inProgress.approve(approval("tgen_other_payment_key", krw(129_000))))
                .hasMessageNotContaining("tgen_other_payment_key")
                .hasMessageNotContaining(PAYMENT_KEY);
        }

        @Test
        @DisplayName("IN_PROGRESS → FAILED 는 거절 코드 · 메시지를 남기고 활성 슬롯을 놓는다")
        void rejects() {
            Payment failed = PaymentFixtures.inProgress().reject("EXCEED_MAX_DAILY_PAYMENT_COUNT", "하루 결제 가능 횟수를 초과했습니다.");

            assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(failed.failureCode()).isEqualTo("EXCEED_MAX_DAILY_PAYMENT_COUNT");
            assertThat(failed.failureMessage()).isEqualTo("하루 결제 가능 횟수를 초과했습니다.");
            assertThat(failed.activeOrderId()).isNull();
        }

        @Test
        @DisplayName("거절 메시지가 510자를 넘으면 잘라 남긴다 — 길이 때문에 실패 기록을 잃지 않게")
        void longFailureMessageIsTruncated() {
            Payment failed = PaymentFixtures.inProgress().reject("REJECT_CARD_COMPANY", "가".repeat(600));

            assertThat(failed.failureMessage()).hasSize(510);
        }

        @Test
        @DisplayName("IN_PROGRESS → UNKNOWN 은 이유를 남기고 활성 슬롯을 놓지 않는다")
        void marksUnknown() {
            Payment unknown = PaymentFixtures.inProgress().markUnknown(PaymentUnknownReason.APPROVAL_MISMATCH);

            assertThat(unknown.status()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(unknown.unknownReason()).isEqualTo(PaymentUnknownReason.APPROVAL_MISMATCH);
            assertThat(unknown.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(unknown.activeOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("IN_PROGRESS 로 오래 남은 행은 STALE_IN_PROGRESS 로 재조회 대상이 된다")
        void marksStale() {
            assertThat(PaymentFixtures.inProgress().markUnknown(PaymentUnknownReason.STALE_IN_PROGRESS).unknownReason())
                .isEqualTo(PaymentUnknownReason.STALE_IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("재조회로 확정 (resolve) · 사람 확인")
    class Resolve {

        @Test
        @DisplayName("PG 가 승인했으면 DONE — 승인과 같은 칸을 채우고 미확정 이유는 이력으로 남는다")
        void resolvesToDone() {
            Payment done = PaymentFixtures.unknown().resolve(new PaymentInquiry.Approved(approval(krw(129_000))));

            assertThat(done.status()).isEqualTo(PaymentStatus.DONE);
            assertThat(done.approvedAt()).isEqualTo(APPROVED_AT);
            assertThat(done.unknownReason()).isEqualTo(PaymentUnknownReason.TIMEOUT);
            assertThat(done.activeOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("PG 가 승인하지 않았으면 FAILED — 활성 슬롯을 놓는다")
        void resolvesToFailed() {
            Payment failed = PaymentFixtures.unknown().resolve(NOT_APPROVED);

            assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(failed.failureCode()).isEqualTo("NOT_FOUND_PAYMENT_SESSION");
            assertThat(failed.unknownReason()).isEqualTo(PaymentUnknownReason.TIMEOUT);
            assertThat(failed.activeOrderId()).isNull();
        }

        @Test
        @DisplayName("조회한 승인 금액이 다르면 확정하지 않는다 (AMOUNT 불일치)")
        void amountMismatchIsRejected() {
            Payment unknown = PaymentFixtures.unknown();

            assertMismatch(() -> unknown.resolve(new PaymentInquiry.Approved(approval(krw(129_001)))), Field.AMOUNT);
        }

        @Test
        @DisplayName("사람 확인으로 넘기면 UNKNOWN 그대로 이유만 바뀐다. 같은 이유면 같은 인스턴스다(멱등)")
        void flagsForManualReview() {
            Payment flagged = PaymentFixtures.unknown().flagForManualReview(PaymentUnknownReason.MANUAL_REVIEW);

            assertThat(flagged.status()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(flagged.unknownReason()).isEqualTo(PaymentUnknownReason.MANUAL_REVIEW);
            assertThat(flagged.activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(flagged.flagForManualReview(PaymentUnknownReason.MANUAL_REVIEW)).isSameAs(flagged);
        }
    }

    @Nested
    @DisplayName("늦은 승인 (recordLateApproval)")
    class LateApproval {

        @Test
        @DisplayName("FAILED → LATE_APPROVED 는 승인 칸을 남기고 거절 코드를 이력으로 둔다 — 활성 슬롯 · 주문 반영은 없다")
        void fromFailed() {
            Payment late = PaymentFixtures.failed().recordLateApproval(approval(krw(129_000)));

            assertThat(late.status()).isEqualTo(PaymentStatus.LATE_APPROVED);
            assertThat(late.approvedAt()).isEqualTo(APPROVED_AT);
            assertThat(late.method()).isEqualTo(PaymentMethod.EASY_PAY);
            assertThat(late.failureCode()).isEqualTo("REJECT_CARD_COMPANY");
            assertThat(late.abortReason()).isNull();
            assertThat(late.activeOrderId()).isNull();
            assertThat(late.orderSynced()).isFalse();
        }

        @Test
        @DisplayName("ABORTED → LATE_APPROVED 는 중단 사유를 이력으로 둔다. 결제키 없이 버린 시도면 승인의 결제키를 남긴다")
        void fromAborted() {
            Payment late = PaymentFixtures.aborted().recordLateApproval(approval(krw(129_000)));

            assertThat(late.status()).isEqualTo(PaymentStatus.LATE_APPROVED);
            assertThat(late.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(late.abortReason()).isEqualTo(PaymentAbortReason.REPREPARED);
            assertThat(late.failureCode()).isNull();
        }

        @Test
        @DisplayName("결제키가 있는 시도에 다른 결제키의 승인은 붙이지 않는다 · 금액이 다르면 붙이지 않는다")
        void mismatchIsRejected() {
            Payment failed = PaymentFixtures.failed();

            assertMismatch(() -> failed.recordLateApproval(approval("tgen_other_payment_key", krw(129_000))), Field.PAYMENT_KEY);
            assertMismatch(() -> failed.recordLateApproval(approval(krw(1_000))), Field.AMOUNT);
        }
    }

    @Nested
    @DisplayName("주문 반영 기록")
    class OrderSync {

        @Test
        @DisplayName("반영 성공은 반영 여부를 true 로 두고 이전 실패 코드를 지운다. 다시 불러도 그대로다(멱등)")
        void marksSynced() {
            Payment syncFailed = PaymentFixtures.done().markOrderSyncFailed("ORDER_030");

            Payment synced = syncFailed.markOrderSynced();

            assertThat(synced.orderSynced()).isTrue();
            assertThat(synced.orderSyncFailureCode()).isNull();
            assertThat(synced.status()).isEqualTo(PaymentStatus.DONE);
            assertThat(synced.markOrderSynced()).isSameAs(synced);
        }

        @Test
        @DisplayName("종결 실패는 코드를 남기고 반영 여부는 false 그대로다. 같은 코드를 다시 불러도 그대로다(멱등)")
        void marksSyncFailed() {
            Payment syncFailed = PaymentFixtures.done().markOrderSyncFailed("ORDER_030");

            assertThat(syncFailed.orderSynced()).isFalse();
            assertThat(syncFailed.orderSyncFailureCode()).isEqualTo("ORDER_030");
            assertThat(syncFailed.markOrderSyncFailed("ORDER_030")).isSameAs(syncFailed);
            assertThat(syncFailed.markOrderSyncFailed("ORDER_031").orderSyncFailureCode()).isEqualTo("ORDER_031");
        }

        @Test
        @DisplayName("이미 반영됐으면 늦게 온 실패가 성공 기록을 덮지 않는다")
        void lateFailureDoesNotOverwriteSynced() {
            Payment synced = PaymentFixtures.done().markOrderSynced();

            assertThat(synced.markOrderSyncFailed("ORDER_030")).isSameAs(synced);
        }

        @Test
        @DisplayName("빈 실패 코드 · 30자를 넘는 실패 코드는 거절한다")
        void invalidSyncFailureCodeIsRejected() {
            Payment done = PaymentFixtures.done();

            assertThatThrownBy(() -> done.markOrderSyncFailed(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> done.markOrderSyncFailed("E".repeat(31))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("상태별 불변식")
    class Invariants {

        @Test
        @DisplayName("승인 시각 없는 DONE 은 만들 수 없다")
        void doneRequiresApprovedAt() {
            assertInvalid(PaymentStatus.DONE, PAYMENT_KEY, PaymentMethod.CARD, STARTED_AT, null, null, null, null);
        }

        @Test
        @DisplayName("중단 사유 있는 READY 는 만들 수 없다")
        void readyMustNotHaveAbortReason() {
            assertInvalid(PaymentStatus.READY, null, null, null, null, null, null, PaymentAbortReason.ADMIN);
        }

        @Test
        @DisplayName("거절 코드 있는 READY 는 만들 수 없다")
        void readyMustNotHaveFailureCode() {
            assertInvalid(PaymentStatus.READY, null, null, null, null, "REJECT_CARD_COMPANY", null, null);
        }

        @Test
        @DisplayName("결제키 없이 승인 시작 시각만 있는 READY 는 만들 수 없다")
        void startedAtRequiresKey() {
            assertInvalid(PaymentStatus.READY, null, null, STARTED_AT, null, null, null, null);
        }

        @Test
        @DisplayName("UNKNOWN 은 미확정 이유가 있어야 하고, READY 에는 미확정 이유가 없어야 한다")
        void unknownReasonPlacement() {
            assertInvalid(PaymentStatus.UNKNOWN, PAYMENT_KEY, null, STARTED_AT, null, null, null, null);
            assertInvalid(PaymentStatus.READY, null, null, null, null, null, PaymentUnknownReason.TIMEOUT, null);
        }

        @Test
        @DisplayName("LATE_APPROVED 는 종결 이유(거절 코드 · 중단 사유) 중 하나만 가진다")
        void lateApprovedOrigin() {
            assertInvalid(PaymentStatus.LATE_APPROVED, PAYMENT_KEY, PaymentMethod.CARD, STARTED_AT, APPROVED_AT, null, null, null);
            assertInvalid(PaymentStatus.LATE_APPROVED, PAYMENT_KEY, PaymentMethod.CARD, STARTED_AT, APPROVED_AT, "REJECT_CARD_COMPANY", null, PaymentAbortReason.ADMIN);
        }

        @Test
        @DisplayName("주문에 반영하지 않는 상태(FAILED · LATE_APPROVED)는 주문 반영으로 기록할 수 없다")
        void orderSyncedRequiresReportingStatus() {
            assertThatThrownBy(() -> new Payment(
                PAYMENT_ID, ORDER_ID, MEMBER_ID, Long.toString(PAYMENT_ID), PaymentGateway.TOSS, krw(1_000), ORDER_NAME, PaymentStatus.LATE_APPROVED,
                PAYMENT_KEY, PaymentMethod.CARD, null, null, STARTED_AT, APPROVED_AT, "REJECT_CARD_COMPANY", null, null, true, null, null, 0L
            )).isInstanceOf(IllegalArgumentException.class);
        }

        private void assertInvalid(
            PaymentStatus status, String paymentKey, PaymentMethod method, Instant startedAt, Instant approvedAt,
            String failureCode, PaymentUnknownReason unknownReason, PaymentAbortReason abortReason
        ) {
            assertThatThrownBy(() -> new Payment(
                PAYMENT_ID, ORDER_ID, MEMBER_ID, Long.toString(PAYMENT_ID), PaymentGateway.TOSS, krw(1_000), ORDER_NAME, status,
                paymentKey, method, null, null, startedAt, approvedAt, failureCode, null, unknownReason, false, null, abortReason, 0L
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("로그 마스킹")
    class Masking {

        @Test
        @DisplayName("toString 은 결제키 앞 8자만 남긴다 — 결제 · 승인 결과 모두")
        void masksPaymentKey() {
            Payment done = PaymentFixtures.done();

            assertThat(done.toString()).contains("paymentKey=tgen_202***").doesNotContain(PAYMENT_KEY);
            assertThat(approval(krw(129_000)).toString()).contains("paymentKey=tgen_202***").doesNotContain(PAYMENT_KEY);
        }

        @Test
        @DisplayName("8자 이하 결제키는 전부 가린다. 결제키가 없으면 null 이다")
        void masksShortKey() {
            assertThat(Payment.maskPaymentKey("abcdefgh")).isEqualTo("***");
            assertThat(Payment.maskPaymentKey(null)).isNull();
            assertThat(PaymentFixtures.ready().toString()).contains("paymentKey=null");
        }

        @Test
        @DisplayName("조회 실패 결과 · 거절된 결제의 toString 은 PG 메시지를 찍지 않는다")
        void hidesFailureMessage() {
            assertThat(NOT_APPROVED.toString()).isEqualTo("Failed[failureCode=NOT_FOUND_PAYMENT_SESSION]");
            assertThat(PaymentFixtures.failed().toString()).doesNotContain("카드사에서 거절했습니다.");
        }
    }

    private static void assertMismatch(ThrowingCallable call, Field field) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(PaymentApprovalMismatchException.class, exception -> {
                assertThat(exception.getField()).isEqualTo(field);
                assertThat(exception.getPaymentId()).isEqualTo(PAYMENT_ID);
            });
    }

    private static Payment withVersion(Payment payment, Long version) {
        return new Payment(
            payment.id(), payment.orderId(), payment.memberId(), payment.pgOrderId(), payment.gateway(), payment.amount(), payment.orderName(), payment.status(),
            payment.paymentKey(), payment.method(), payment.easyPayProvider(), payment.pgStatus(), payment.approvalStartedAt(), payment.approvedAt(),
            payment.failureCode(), payment.failureMessage(), payment.unknownReason(), payment.orderSynced(), payment.orderSyncFailureCode(),
            payment.abortReason(), version
        );
    }
}
