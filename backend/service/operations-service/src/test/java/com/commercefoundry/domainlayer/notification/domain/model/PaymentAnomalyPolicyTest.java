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
        return new PaymentAnomalyObservation(at, unknown, manual, ZERO, ZERO, ZERO, ZERO);
    }
    static Decision accept(State previous, PaymentAnomalyObservation sample) {
        return PaymentAnomalyPolicy.accept(previous, sample, sample.observedAt(), SETTINGS);
    }
    static Stream<Arguments> boundaries() {
        return Stream.of(Arguments.of(Family.UNKNOWN, 300), Arguments.of(Family.IN_PROGRESS, 300), Arguments.of(Family.RETURNED_READY, 600),
            Arguments.of(Family.LATE_APPROVED, 0), Arguments.of(Family.DONE_ORDER_UNSYNCED, 600));
    }
    static PaymentAnomalyObservation lane(Family family, Instant at, Instant oldest) {
        var positive = fact(1, oldest);
        return new PaymentAnomalyObservation(at, family == Family.UNKNOWN ? positive : ZERO, ZERO,
            family == Family.IN_PROGRESS ? positive : ZERO, family == Family.RETURNED_READY ? positive : ZERO,
            family == Family.LATE_APPROVED ? positive : ZERO, family == Family.DONE_ORDER_UNSYNCED ? positive : ZERO);
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
        assertThat(decision.state().incidents()).hasSize(5);
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
    void fiveFamiliesDecideTogetherAndRepeatExactlyAtSuppressionBoundary() {
        var value = fact(4, T.minusSeconds(600));
        var all = new PaymentAnomalyObservation(T, value, fact(1, T), value, value, value, value);
        var opened = accept(null, all);
        assertThat(opened.notices()).hasSize(5);
        var beforeSample = new PaymentAnomalyObservation(T.plusSeconds(3599), value, ZERO, value, value, value, value);
        var before = accept(opened.state(), beforeSample);
        assertThat(before.notices()).isEmpty();
        var boundarySample = new PaymentAnomalyObservation(T.plusSeconds(3600), value, ZERO, value, value, value, value);
        var boundary = accept(before.state(), boundarySample);
        assertThat(boundary.notices()).hasSize(5).allSatisfy((family, notice) -> assertThat(notice.signal()).isEqualTo(Signal.REPEATED));
        var empty = accept(boundary.state(), sample(T.plusSeconds(3601), ZERO, ZERO));
        assertThat(empty.notices()).hasSize(5).allSatisfy((family, notice) -> assertThat(notice.severity()).isEqualTo(NotificationSeverity.INFO));
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
            Duration.ZERO, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(30));
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
    void settingsValidateAllDurationsAndOnlyTwoThresholdsAllowZero() {
        var valid = List.of(SETTINGS.unknown(), SETTINGS.manualReviewUnknown(), SETTINGS.inProgress(), SETTINGS.returnedReady(),
            SETTINGS.lateApproved(), SETTINGS.doneOrderUnsynced(), SETTINGS.suppression(), SETTINGS.maxObservationAge());
        for (int index = 0; index < valid.size(); index++) {
            for (var invalid : List.of(Duration.ofNanos(-1), index == 6 ? Duration.ofDays(7).plusNanos(1)
                : index == 7 ? Duration.ofMinutes(5).plusNanos(1) : Duration.ofDays(1).plusNanos(1))) {
                var values = valid.toArray(Duration[]::new);
                values[index] = invalid;
                assertThatThrownBy(() -> settings(values)).isInstanceOf(IllegalArgumentException.class);
            }
            if (index != 1 && index != 4) {
                var values = valid.toArray(Duration[]::new);
                values[index] = Duration.ZERO;
                assertThatThrownBy(() -> settings(values)).isInstanceOf(IllegalArgumentException.class);
            }
        }
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
        incidents.remove(Family.IN_PROGRESS);
        assertThatThrownBy(() -> new State(T, T, incidents)).isInstanceOf(IllegalArgumentException.class);
    }

    private PaymentAnomalyPolicySettings settings(Duration[] values) {
        return new PaymentAnomalyPolicySettings(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]);
    }
}
