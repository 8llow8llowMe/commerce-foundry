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

import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException.Action;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-21T02:00:00Z");

    /** 시작 상태 — 전이 표의 행. 취소 상태는 들어오는 전이가 없어 만들 수 없다. */
    private static final Map<PaymentStatus, Supplier<Payment>> STATES = Map.of(
        PaymentStatus.READY, PaymentFixtures::ready,
        PaymentStatus.IN_PROGRESS, PaymentFixtures::inProgress,
        PaymentStatus.DONE, PaymentFixtures::done,
        PaymentStatus.FAILED, PaymentFixtures::failed,
        PaymentStatus.UNKNOWN, PaymentFixtures::unknown,
        PaymentStatus.ABORTED, PaymentFixtures::aborted
    );

    /** 전이 메서드 — 전이 표의 열. */
    private static final Map<Action, UnaryOperator<Payment>> ACTIONS = Map.of(
        Action.START_APPROVAL, payment -> payment.startApproval(PAYMENT_KEY, NOW),
        Action.ABORT, payment -> payment.abort(PaymentAbortReason.AMOUNT_CHANGED),
        Action.APPROVE, payment -> payment.approve(approval(krw(129_000))),
        Action.REJECT, payment -> payment.reject("REJECT_CARD_COMPANY", null),
        Action.MARK_UNKNOWN, payment -> payment.markUnknown(PaymentUnknownReason.SERVER_ERROR),
        Action.REVERT_TO_READY, Payment::revertToReady,
        Action.RESOLVE, payment -> payment.resolve(new PaymentInquiry.Failed("NOT_FOUND_PAYMENT_SESSION", null)),
        Action.MARK_ORDER_SYNCED, Payment::markOrderSynced,
        Action.MARK_ORDER_SYNC_FAILED, payment -> payment.markOrderSyncFailed("ORDER_030")
    );

    /** 허용 전이 표 — 현재 상태 → 허용된 동작. 나머지 칸은 전부 거절이다. */
    private static final Map<PaymentStatus, Set<Action>> ALLOWED = Map.of(
        PaymentStatus.READY, Set.of(Action.START_APPROVAL, Action.ABORT),
        PaymentStatus.IN_PROGRESS, Set.of(Action.APPROVE, Action.REJECT, Action.MARK_UNKNOWN, Action.REVERT_TO_READY),
        PaymentStatus.UNKNOWN, Set.of(Action.RESOLVE),
        PaymentStatus.DONE, Set.of(Action.MARK_ORDER_SYNCED, Action.MARK_ORDER_SYNC_FAILED),
        PaymentStatus.FAILED, Set.of(),
        PaymentStatus.ABORTED, Set.of()
    );

    static Stream<Arguments> forbiddenTransitions() {
        List<Arguments> forbidden = new ArrayList<>();
        STATES.forEach((status, state) -> ACTIONS.forEach((action, transition) -> {
            if (!ALLOWED.get(status).contains(action)) {
                forbidden.add(Arguments.of(status, action, state, transition));
            }
        }));
        return forbidden.stream();
    }

    static Stream<Arguments> allowedTransitions() {
        List<Arguments> allowed = new ArrayList<>();
        ALLOWED.forEach((status, actions) -> actions.forEach(action -> allowed.add(Arguments.of(status, action))));
        return allowed.stream();
    }

    @ParameterizedTest(name = "{0} 에서 {1} 은 거절된다")
    @MethodSource("forbiddenTransitions")
    @DisplayName("전이 표 밖은 현재 상태 · 시도한 동작을 실은 PaymentTransitionException 이다")
    void forbiddenTransitionIsRejected(PaymentStatus status, Action action, Supplier<Payment> state, UnaryOperator<Payment> transition) {
        Payment payment = state.get();

        assertThatThrownBy(() -> transition.apply(payment))
            .isInstanceOfSatisfying(PaymentTransitionException.class, exception -> {
                assertThat(exception.getCurrentStatus()).isEqualTo(status);
                assertThat(exception.getAttemptedAction()).isEqualTo(action);
            });
    }

    @ParameterizedTest(name = "{0} 에서 {1}")
    @MethodSource("allowedTransitions")
    @DisplayName("전이 표 안의 칸은 모두 통과한다 — 표와 구현이 같다")
    void allowedTransitionPasses(PaymentStatus status, Action action) {
        Payment payment = STATES.get(status).get();

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
        @DisplayName("짧거나(5자) · 길거나(65자) · 허용 밖 문자가 있으면 거절한다")
        void rejectsInvalid(String pgOrderId) {
            assertThatThrownBy(() -> readyWith(pgOrderId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PG 주문번호");
        }
    }

    @Nested
    @DisplayName("활성 슬롯 (activeOrderId)")
    class ActiveOrderId {

        @Test
        @DisplayName("READY · IN_PROGRESS · UNKNOWN · DONE 은 주문 아이디, FAILED · ABORTED 는 null 이다")
        void activeOrderIdByStatus() {
            assertThat(PaymentFixtures.ready().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.inProgress().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.unknown().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.done().activeOrderId()).isEqualTo(ORDER_ID);
            assertThat(PaymentFixtures.failed().activeOrderId()).isNull();
            assertThat(PaymentFixtures.aborted().activeOrderId()).isNull();
        }

        @Test
        @DisplayName("취소 상태는 승인된 결제와 같이 활성으로 본다 (닿지 않는 상태 — 취소 이슈에서 다시 정한다)")
        void canceledStatusesKeepSlot() {
            assertThat(PaymentStatus.PARTIAL_CANCELED.releasesActiveSlot()).isFalse();
            assertThat(PaymentStatus.CANCELED.releasesActiveSlot()).isFalse();
            assertThat(PaymentStatus.CANCELED.isApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("승인 시작 · 되돌림")
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
        @DisplayName("IN_PROGRESS → READY 는 결제키를 그대로 두고 승인 시작 시각을 지운다 — 다시 승인 시작할 수 있다")
        void revertKeepsPaymentKey() {
            Payment reverted = PaymentFixtures.inProgress().revertToReady();

            assertThat(reverted.status()).isEqualTo(PaymentStatus.READY);
            assertThat(reverted.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(reverted.approvalStartedAt()).isNull();
            assertThat(reverted.activeOrderId()).isEqualTo(ORDER_ID);

            Payment restarted = reverted.startApproval("tgen_reauthenticated0001", NOW);
            assertThat(restarted.paymentKey()).isEqualTo("tgen_reauthenticated0001");
            assertThat(restarted.approvalStartedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("READY → ABORTED 는 사유를 남기고 활성 슬롯을 놓는다")
        void abortReleasesSlot() {
            Payment aborted = PaymentFixtures.ready().abort(PaymentAbortReason.ORDER_EXPIRED);

            assertThat(aborted.status()).isEqualTo(PaymentStatus.ABORTED);
            assertThat(aborted.abortReason()).isEqualTo(PaymentAbortReason.ORDER_EXPIRED);
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
        @DisplayName("승인 금액이 결제 금액과 다르면 승인으로 기록하지 않는다")
        void amountMismatchIsRejected() {
            Payment inProgress = PaymentFixtures.inProgress();

            assertThatThrownBy(() -> inProgress.approve(approval(krw(1_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("승인 금액");
        }

        @Test
        @DisplayName("승인 결과의 결제키가 다르면 승인으로 기록하지 않는다")
        void paymentKeyMismatchIsRejected() {
            Payment inProgress = PaymentFixtures.inProgress();

            assertThatThrownBy(() -> inProgress.approve(approval("tgen_other_payment_key", krw(129_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("결제키");
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
            Payment unknown = PaymentFixtures.inProgress().markUnknown(PaymentUnknownReason.AMOUNT_MISMATCH_ON_APPROVAL);

            assertThat(unknown.status()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(unknown.unknownReason()).isEqualTo(PaymentUnknownReason.AMOUNT_MISMATCH_ON_APPROVAL);
            assertThat(unknown.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(unknown.activeOrderId()).isEqualTo(ORDER_ID);
        }
    }

    @Nested
    @DisplayName("재조회로 확정 (resolve)")
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
            Payment failed = PaymentFixtures.unknown().resolve(new PaymentInquiry.Failed("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다."));

            assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(failed.failureCode()).isEqualTo("NOT_FOUND_PAYMENT_SESSION");
            assertThat(failed.unknownReason()).isEqualTo(PaymentUnknownReason.TIMEOUT);
            assertThat(failed.activeOrderId()).isNull();
        }

        @Test
        @DisplayName("조회한 승인 금액이 다르면 확정하지 않는다")
        void amountMismatchIsRejected() {
            Payment unknown = PaymentFixtures.unknown();

            assertThatThrownBy(() -> unknown.resolve(new PaymentInquiry.Approved(approval(krw(129_001)))))
                .isInstanceOf(IllegalArgumentException.class);
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
            assertThatThrownBy(() -> new Payment(
                PAYMENT_ID, ORDER_ID, MEMBER_ID, Long.toString(PAYMENT_ID), PaymentGateway.TOSS, krw(1_000), ORDER_NAME, PaymentStatus.DONE,
                PAYMENT_KEY, PaymentMethod.CARD, null, null, STARTED_AT, null, null, null, null, false, null, null, 0L
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("중단 사유 있는 READY 는 만들 수 없다")
        void readyMustNotHaveAbortReason() {
            assertThatThrownBy(() -> new Payment(
                PAYMENT_ID, ORDER_ID, MEMBER_ID, Long.toString(PAYMENT_ID), PaymentGateway.TOSS, krw(1_000), ORDER_NAME, PaymentStatus.READY,
                null, null, null, null, null, null, null, null, null, false, null, PaymentAbortReason.ADMIN, 0L
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("승인되지 않은 결제는 주문 반영으로 기록할 수 없다")
        void orderSyncedRequiresApproved() {
            assertThatThrownBy(() -> new Payment(
                PAYMENT_ID, ORDER_ID, MEMBER_ID, Long.toString(PAYMENT_ID), PaymentGateway.TOSS, krw(1_000), ORDER_NAME, PaymentStatus.FAILED,
                PAYMENT_KEY, null, null, null, STARTED_AT, null, "REJECT_CARD_COMPANY", null, null, true, null, null, 0L
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("UNKNOWN 은 미확정 이유가 있어야 한다")
        void unknownRequiresReason() {
            assertThatThrownBy(() -> new Payment(
                PAYMENT_ID, ORDER_ID, MEMBER_ID, Long.toString(PAYMENT_ID), PaymentGateway.TOSS, krw(1_000), ORDER_NAME, PaymentStatus.UNKNOWN,
                PAYMENT_KEY, null, null, null, STARTED_AT, null, null, null, null, false, null, null, 0L
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("결제키 마스킹")
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
