package com.commercefoundry.domainlayer.notification.domain.model;

import static com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentAnomalyPolicyTest {
    private static final Instant T = Instant.parse("2026-10-01T00:00:00.123456Z");
    private static final PaymentAnomalyPolicySettings SETTINGS = PaymentAnomalyPolicySettings.defaults();
    private static final PaymentAnomalyObservation.Fact ZERO = new PaymentAnomalyObservation.Fact(0, null);

    static PaymentAnomalyObservation.Fact fact(long count, Instant oldest) {
        return new PaymentAnomalyObservation.Fact(count, oldest);
    }
    static PaymentAnomalyObservation sample(Instant at, PaymentAnomalyObservation.Fact unknown, PaymentAnomalyObservation.Fact manual) {
        return new PaymentAnomalyObservation(at, unknown, manual, ZERO, ZERO, ZERO, ZERO, ZERO);
    }
    /** 다른 여섯 버킷은 0건이고 7번째 버킷만 바꾼다. null 은 미관측이다. */
    static PaymentAnomalyObservation autoCancel(Instant at, PaymentAnomalyObservation.Fact value) {
        return new PaymentAnomalyObservation(at, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, value);
    }
    static Decision accept(State previous, PaymentAnomalyObservation sample) {
        return PaymentAnomalyPolicy.accept(previous, sample, sample.observedAt(), SETTINGS);
    }
    static Stream<Arguments> boundaries() {
        return Stream.of(Arguments.of(Family.UNKNOWN, 300), Arguments.of(Family.IN_PROGRESS, 300), Arguments.of(Family.RETURNED_READY, 600),
            Arguments.of(Family.LATE_APPROVED, 0), Arguments.of(Family.DONE_ORDER_UNSYNCED, 600), Arguments.of(Family.AUTO_CANCEL_MANUAL_REVIEW, 0));
    }
    static PaymentAnomalyObservation lane(Family family, Instant at, Instant oldest) {
        var positive = fact(1, oldest);
        return new PaymentAnomalyObservation(at, family == Family.UNKNOWN ? positive : ZERO, ZERO,
            family == Family.IN_PROGRESS ? positive : ZERO, family == Family.RETURNED_READY ? positive : ZERO,
            family == Family.LATE_APPROVED ? positive : ZERO, family == Family.DONE_ORDER_UNSYNCED ? positive : ZERO,
            family == Family.AUTO_CANCEL_MANUAL_REVIEW ? positive : ZERO);
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void exactAgeThresholdAndFirstDetectedAt(Family family, long seconds) {
        if (seconds > 0) {
            var below = accept(null, lane(family, T, T.minusSeconds(seconds).plusNanos(1000)));
            assertThat(below.notices()).isEmpty();
            assertThat(below.state().lastObservedAt()).isEqualTo(T);
        }
        var decision = accept(null, lane(family, T, T.minusSeconds(seconds)));
        assertThat(decision.notices()).containsOnlyKeys(family);
        assertThat(decision.notices().get(family).signal()).isEqualTo(Signal.OPENED);
        assertThat(decision.state().incidents().get(family).firstDetectedAt()).isEqualTo(T);
    }

    @Test
    void manualSubsetStartsOnlyOneCriticalIncidentWithoutSubtractingOldest() {
        var decision = accept(null, sample(T, fact(3, T), fact(3, T)));
        assertThat(decision.notices()).containsExactly(entry(Family.UNKNOWN, new Notice(Signal.OPENED, NotificationSeverity.CRITICAL)));
        assertThat(decision.state().incidents()).hasSize(6);
    }

    @Test
    void manualEscalatesOnceDuringSuppressionAndRestartsSuppression() {
        var opened = accept(null, sample(T, fact(4, T.minusSeconds(300)), ZERO)).state();
        var at = T.plusSeconds(1);
        var escalated = accept(opened, sample(at, fact(4, T.minusSeconds(300)), fact(1, at)));
        assertThat(escalated.notices()).containsExactly(entry(Family.UNKNOWN, new Notice(Signal.ESCALATED, NotificationSeverity.CRITICAL)));
        assertThat(escalated.state().incidents().get(Family.UNKNOWN).firstDetectedAt()).isEqualTo(T);
        var absent = accept(escalated.state(), sample(T.plusSeconds(2), fact(3, T.minusSeconds(300)), ZERO));
        var reappeared = accept(absent.state(), sample(T.plusSeconds(3), fact(3, T.minusSeconds(300)), fact(1, T)));
        assertThat(absent.notices()).isEmpty();
        assertThat(reappeared.notices()).isEmpty();
        assertThat(reappeared.state().incidents().get(Family.UNKNOWN).severity()).isEqualTo(NotificationSeverity.CRITICAL);
        var before = accept(reappeared.state(), sample(T.plusSeconds(3600), fact(2, T), ZERO));
        assertThat(before.notices()).isEmpty();
        var boundary = accept(before.state(), sample(T.plusSeconds(3601), fact(2, T), ZERO));
        assertThat(boundary.notices()).containsExactly(entry(Family.UNKNOWN, new Notice(Signal.REPEATED, NotificationSeverity.CRITICAL)));
    }

    @Test
    void positiveYoungerCandidateKeepsIncidentWithoutRepeatAndOnlyValidZeroEndsIt() {
        var opened = accept(null, sample(T, fact(2, T.minusSeconds(300)), ZERO)).state();
        var younger = accept(opened, sample(T.plusSeconds(3600), fact(1, T.plusSeconds(3600)), ZERO));
        assertThat(younger.notices()).isEmpty();
        assertThat(younger.state().incidents().get(Family.UNKNOWN)).isEqualTo(opened.incidents().get(Family.UNKNOWN));
        var ended = accept(younger.state(), sample(T.plusSeconds(3601), ZERO, ZERO));
        assertThat(ended.notices()).containsExactly(entry(Family.UNKNOWN, new Notice(Signal.CANDIDATES_DISAPPEARED, NotificationSeverity.INFO)));
        assertThat(accept(ended.state(), sample(T.plusSeconds(3602), ZERO, ZERO)).notices()).isEmpty();
        var reopened = accept(ended.state(), sample(T.plusSeconds(3603), fact(1, T), ZERO));
        assertThat(reopened.notices().get(Family.UNKNOWN).signal()).isEqualTo(Signal.OPENED);
        assertThat(reopened.state().incidents().get(Family.UNKNOWN).firstDetectedAt()).isEqualTo(T.plusSeconds(3603));
    }

    @Test
    void sixFamiliesDecideTogetherAndRepeatExactlyAtSuppressionBoundary() {
        var value = fact(4, T.minusSeconds(600));
        var all = new PaymentAnomalyObservation(T, value, fact(1, T), value, value, value, value, value);
        var opened = accept(null, all);
        assertThat(opened.notices()).hasSize(6);
        var beforeSample = new PaymentAnomalyObservation(T.plusSeconds(3599), value, ZERO, value, value, value, value, value);
        var before = accept(opened.state(), beforeSample);
        assertThat(before.notices()).isEmpty();
        var boundarySample = new PaymentAnomalyObservation(T.plusSeconds(3600), value, ZERO, value, value, value, value, value);
        var boundary = accept(before.state(), boundarySample);
        assertThat(boundary.notices()).hasSize(6).allSatisfy((family, notice) -> assertThat(notice.signal()).isEqualTo(Signal.REPEATED));
        var empty = accept(boundary.state(), sample(T.plusSeconds(3601), ZERO, ZERO));
        assertThat(empty.notices()).hasSize(6).allSatisfy((family, notice) -> assertThat(notice.severity()).isEqualTo(NotificationSeverity.INFO));
    }

    @Test
    void autoCancelManualReviewOpensCriticalAtZeroThresholdRepeatsAtSuppressionBoundaryAndEndsOnce() {
        var opened = accept(null, autoCancel(T, fact(2, T)));
        assertThat(opened.notices()).containsExactly(entry(Family.AUTO_CANCEL_MANUAL_REVIEW, new Notice(Signal.OPENED, NotificationSeverity.CRITICAL)));
        assertThat(opened.state().incidents().get(Family.AUTO_CANCEL_MANUAL_REVIEW))
            .isEqualTo(new Incident(Status.ACTIVE, NotificationSeverity.CRITICAL, T, T));
        // UNKNOWN 수동 확인과 달리 승격 경로가 없다 — 억제 중에는 신호가 없다
        var suppressed = accept(opened.state(), autoCancel(T.plusSeconds(3599), fact(3, T.minusSeconds(60))));
        assertThat(suppressed.notices()).isEmpty();
        var repeated = accept(suppressed.state(), autoCancel(T.plusSeconds(3600), fact(3, T.minusSeconds(60))));
        assertThat(repeated.notices()).containsExactly(entry(Family.AUTO_CANCEL_MANUAL_REVIEW, new Notice(Signal.REPEATED, NotificationSeverity.CRITICAL)));
        assertThat(repeated.state().incidents().get(Family.AUTO_CANCEL_MANUAL_REVIEW).firstDetectedAt()).isEqualTo(T);
        var ended = accept(repeated.state(), autoCancel(T.plusSeconds(3601), ZERO));
        assertThat(ended.notices()).containsExactly(entry(Family.AUTO_CANCEL_MANUAL_REVIEW,
            new Notice(Signal.CANDIDATES_DISAPPEARED, NotificationSeverity.INFO)));
        assertThat(ended.state().incidents().get(Family.AUTO_CANCEL_MANUAL_REVIEW).status()).isEqualTo(Status.NORMAL);
        assertThat(accept(ended.state(), autoCancel(T.plusSeconds(3602), ZERO)).notices()).isEmpty();
    }

    @Test
    void unobservedAutoCancelKeepsIncidentWithoutSignalOrResolutionWhileWatermarksAdvance() {
        var current = accept(null, autoCancel(T, fact(1, T))).state();
        var incident = current.incidents().get(Family.AUTO_CANCEL_MANUAL_REVIEW);
        // 억제 경계를 넘겨도 미관측이면 반복하지 않고, 0건 해소로도 바꾸지 않는다
        for (long seconds : List.of(1L, 3600L, 7200L)) {
            var unobserved = accept(current, autoCancel(T.plusSeconds(seconds), null));
            assertThat(unobserved.notices()).isEmpty();
            assertThat(unobserved.state().incidents().get(Family.AUTO_CANCEL_MANUAL_REVIEW)).isSameAs(incident);
            assertThat(unobserved.state().lastObservedAt()).isEqualTo(T.plusSeconds(seconds));
            assertThat(unobserved.state().lastAcceptedLocalAt()).isEqualTo(T.plusSeconds(seconds));
            current = unobserved.state();
        }
        // 다시 관측되면 기존 사건 그대로 반복·해소 규칙을 따른다
        var repeated = accept(current, autoCancel(T.plusSeconds(7201), fact(1, T)));
        assertThat(repeated.notices()).containsExactly(entry(Family.AUTO_CANCEL_MANUAL_REVIEW, new Notice(Signal.REPEATED, NotificationSeverity.CRITICAL)));
        var ended = accept(repeated.state(), autoCancel(T.plusSeconds(7202), ZERO));
        assertThat(ended.notices().get(Family.AUTO_CANCEL_MANUAL_REVIEW).signal()).isEqualTo(Signal.CANDIDATES_DISAPPEARED);
        // 최초 표본이 미관측이면 NORMAL 로 시작하고 신호가 없다
        var first = accept(null, autoCancel(T, null));
        assertThat(first.notices()).isEmpty();
        assertThat(first.state().incidents().get(Family.AUTO_CANCEL_MANUAL_REVIEW).status()).isEqualTo(Status.NORMAL);
    }

    @Test
    void autoCancelObservationDoesNotChangeOtherFamilyDecisionsAndViceVersa() {
        var unknownOnly = new PaymentAnomalyObservation(T, fact(1, T.minusSeconds(300)), ZERO, ZERO, ZERO, ZERO, ZERO, null);
        var withoutAutoCancel = accept(null, unknownOnly);
        assertThat(withoutAutoCancel.notices()).containsOnlyKeys(Family.UNKNOWN);
        var both = accept(null, new PaymentAnomalyObservation(T, fact(1, T.minusSeconds(300)), ZERO, ZERO, ZERO, ZERO, ZERO, fact(5, T)));
        assertThat(both.notices()).containsOnlyKeys(Family.UNKNOWN, Family.AUTO_CANCEL_MANUAL_REVIEW);
        assertThat(both.notices().get(Family.UNKNOWN)).isEqualTo(withoutAutoCancel.notices().get(Family.UNKNOWN));
        // 미관측 중에도 다른 사건은 평소처럼 해소된다
        var ended = accept(both.state(), new PaymentAnomalyObservation(T.plusSeconds(1), ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, null));
        assertThat(ended.notices()).containsExactly(entry(Family.UNKNOWN, new Notice(Signal.CANDIDATES_DISAPPEARED, NotificationSeverity.INFO)));
        assertThat(ended.state().incidents().get(Family.AUTO_CANCEL_MANUAL_REVIEW).status()).isEqualTo(Status.ACTIVE);
        // UNKNOWN 수동 확인 부분집합과 합산·포함 관계가 없다 — 수동 확인 0건이어도 자동 취소 수동 확인은 많을 수 있다
        assertThatCode(() -> new PaymentAnomalyObservation(T, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, fact(Long.MAX_VALUE, T))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new PaymentAnomalyObservation(T, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, fact(1, T.plusNanos(1000))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectedSamplesKeepEveryStateAndWatermarkIncludingFirstNormal() {
        var normal = accept(null, sample(T, ZERO, ZERO));
        assertThat(normal.notices()).isEmpty();
        assertThat(normal.state().lastAcceptedLocalAt()).isEqualTo(T);
        for (var at : List.of(T, T.minusSeconds(1), T.plusSeconds(31))) {
            var rejected = PaymentAnomalyPolicy.accept(normal.state(), sample(at, ZERO, ZERO), T.plusSeconds(30), SETTINGS);
            assertThat(rejected.state()).isSameAs(normal.state());
            assertThat(rejected.notices()).isEmpty();
        }
        var stale = PaymentAnomalyPolicy.accept(normal.state(), sample(T.plusSeconds(1), ZERO, ZERO), T.plusSeconds(32), SETTINGS);
        assertThat(stale.state()).isSameAs(normal.state());
        var localReversal = PaymentAnomalyPolicy.accept(normal.state(), sample(T.plusSeconds(1), ZERO, ZERO), T.minusSeconds(1), SETTINGS);
        assertThat(localReversal.state()).isSameAs(normal.state());
        var accepted = PaymentAnomalyPolicy.accept(normal.state(), sample(T.plusSeconds(1), ZERO, ZERO), T.plusSeconds(31), SETTINGS);
        assertThat(accepted.state().lastObservedAt()).isEqualTo(T.plusSeconds(1));
        assertThat(PaymentAnomalyPolicy.accept(normal.state(), null, T, SETTINGS).state()).isSameAs(normal.state());
    }

    @Test
    void localClockCannotRecedeEvenWhenNewSourceIsNotInFuture() {
        var previous = PaymentAnomalyPolicy.accept(null, sample(T, ZERO, ZERO), T.plusSeconds(20), SETTINGS).state();
        var rejected = PaymentAnomalyPolicy.accept(previous, sample(T.plusSeconds(1), ZERO, ZERO), T.plusSeconds(19), SETTINGS);
        assertThat(rejected.state()).isSameAs(previous);
    }

    @Test
    void manualThresholdCanBePositiveAndSubsetDisappearanceDoesNotEndUnknown() {
        var config = new PaymentAnomalyPolicySettings(Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ZERO, Duration.ofMinutes(10), Duration.ZERO, Duration.ofHours(1), Duration.ofSeconds(30));
        var before = PaymentAnomalyPolicy.accept(null, sample(T, fact(2, T.minusSeconds(9)), fact(1, T.minusSeconds(9))), T, config);
        assertThat(before.notices()).isEmpty();
        var opened = PaymentAnomalyPolicy.accept(before.state(), sample(T.plusSeconds(1), fact(2, T.minusSeconds(9)), fact(1, T.minusSeconds(9))),
            T.plusSeconds(1), config);
        assertThat(opened.notices().get(Family.UNKNOWN).severity()).isEqualTo(NotificationSeverity.CRITICAL);
        var subsetGone = accept(opened.state(), sample(T.plusSeconds(2), fact(1, T), ZERO));
        assertThat(subsetGone.notices()).isEmpty();
        assertThat(subsetGone.state().incidents().get(Family.UNKNOWN).status()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void autoCancelThresholdCanBePositiveUpToOneDay() {
        var config = new PaymentAnomalyPolicySettings(Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ZERO, Duration.ofMinutes(10), Duration.ofDays(1), Duration.ofHours(1), Duration.ofSeconds(30));
        var below = PaymentAnomalyPolicy.accept(null, autoCancel(T, fact(1, T.minus(Duration.ofDays(1)).plusNanos(1000))), T, config);
        assertThat(below.notices()).isEmpty();
        var opened = PaymentAnomalyPolicy.accept(null, autoCancel(T, fact(1, T.minus(Duration.ofDays(1)))), T, config);
        assertThat(opened.notices()).containsExactly(entry(Family.AUTO_CANCEL_MANUAL_REVIEW, new Notice(Signal.OPENED, NotificationSeverity.CRITICAL)));
    }

    @Test
    void settingsValidateAllDurationsAndOnlyThreeThresholdsAllowZero() {
        var valid = List.of(SETTINGS.unknown(), SETTINGS.manualReviewUnknown(), SETTINGS.inProgress(), SETTINGS.returnedReady(),
            SETTINGS.lateApproved(), SETTINGS.doneOrderUnsynced(), SETTINGS.autoCancelManualReview(), SETTINGS.suppression(), SETTINGS.maxObservationAge());
        assertThat(SETTINGS.autoCancelManualReview()).isEqualTo(Duration.ZERO);
        for (int index = 0; index < valid.size(); index++) {
            for (var invalid : List.of(Duration.ofNanos(-1), index == 7 ? Duration.ofDays(7).plusNanos(1)
                : index == 8 ? Duration.ofMinutes(5).plusNanos(1) : Duration.ofDays(1).plusNanos(1))) {
                var values = valid.toArray(Duration[]::new);
                values[index] = invalid;
                assertThatThrownBy(() -> settings(values)).isInstanceOf(IllegalArgumentException.class);
            }
            if (index != 1 && index != 4 && index != 6) {
                var values = valid.toArray(Duration[]::new);
                values[index] = Duration.ZERO;
                assertThatThrownBy(() -> settings(values)).isInstanceOf(IllegalArgumentException.class);
            }
        }
        var maximum = valid.toArray(Duration[]::new);
        maximum[6] = Duration.ofDays(1);
        assertThat(settings(maximum).autoCancelManualReview()).isEqualTo(Duration.ofDays(1));
        var missing = valid.toArray(Duration[]::new);
        missing[6] = null;
        assertThatThrownBy(() -> settings(missing)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreRejectsIncompleteOrImpossibleStateAndDecisionIsImmutable() {
        var decision = accept(null, lane(Family.UNKNOWN, T, T.minusSeconds(300)));
        var incidents = new EnumMap<Family, Incident>(decision.state().incidents());
        assertThatThrownBy(() -> decision.state().incidents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decision.notices().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new State(T, T.minusSeconds(1), incidents)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new State(T.plusNanos(1), T.plusSeconds(1), incidents)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Incident(Status.ACTIVE, NotificationSeverity.WARNING, T, T.minusSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        incidents.put(Family.IN_PROGRESS, new Incident(Status.ACTIVE, NotificationSeverity.CRITICAL, T, T));
        assertThatThrownBy(() -> new State(T, T, incidents)).isInstanceOf(IllegalArgumentException.class);
        incidents.put(Family.IN_PROGRESS, new Incident(Status.NORMAL, null, null, null));
        // 자동 취소 수동 확인은 CRITICAL 고정이다 — WARNING 활성 상태는 복원하지 않는다
        incidents.put(Family.AUTO_CANCEL_MANUAL_REVIEW, new Incident(Status.ACTIVE, NotificationSeverity.WARNING, T, T));
        assertThatThrownBy(() -> new State(T, T, incidents)).isInstanceOf(IllegalArgumentException.class);
        incidents.put(Family.AUTO_CANCEL_MANUAL_REVIEW, new Incident(Status.ACTIVE, NotificationSeverity.CRITICAL, T, T));
        assertThat(new State(T, T, incidents).incidents()).hasSize(6);
        // 이전 구성의 다섯 사건만으로는 상태를 만들지 않는다 — 빠진 사건을 자동 보충하지 않는다
        incidents.remove(Family.AUTO_CANCEL_MANUAL_REVIEW);
        assertThatThrownBy(() -> new State(T, T, incidents)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void familyNameOrderMatchesDatabaseOrderByFamily() {
        // 저장 어댑터와 계약 테스트는 Enum::name 정렬을 ORDER BY family 와 같다고 본다. 첫 글자가 모두 달라 collation 과 무관하다.
        assertThat(Stream.of(Family.values()).map(Enum::name).sorted().toList()).containsExactly(
            "AUTO_CANCEL_MANUAL_REVIEW", "DONE_ORDER_UNSYNCED", "IN_PROGRESS", "LATE_APPROVED", "RETURNED_READY", "UNKNOWN");
        assertThat(Stream.of(Family.values()).map(family -> family.name().charAt(0)).distinct()).hasSize(Family.values().length);
    }

    private PaymentAnomalyPolicySettings settings(Duration[] values) {
        return new PaymentAnomalyPolicySettings(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8]);
    }
}
