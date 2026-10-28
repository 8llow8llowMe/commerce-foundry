package com.commercefoundry.domainlayer.onboarding.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TourProgressTest {

    private static final Instant FIRST = Instant.parse("2026-10-28T01:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-28T02:00:00Z");

    private static TourProgress stored(int version, TourStatus status, Integer step) {
        return new TourProgress(10L, 7L, "console", version, status, step, status.isFinished() ? FIRST : null, 3L);
    }

    @Nested
    @DisplayName("첫 기록")
    class Start {

        @Test
        @DisplayName("진행 중은 끝난 시각이 없고, 완료 · 건너뜀은 그 순간을 남긴다")
        void finishedAtOnlyWhenFinished() {
            assertThat(TourProgress.start(1L, 7L, "console", 1, TourStatus.IN_PROGRESS, 2, FIRST).finishedAt()).isNull();
            assertThat(TourProgress.start(1L, 7L, "console", 1, TourStatus.COMPLETED, 5, FIRST).finishedAt()).isEqualTo(FIRST);
            assertThat(TourProgress.start(1L, 7L, "console", 1, TourStatus.SKIPPED, null, FIRST).finishedAt()).isEqualTo(FIRST);
        }

        @Test
        @DisplayName("미시작은 행으로 만들지 않는다")
        void notStartedIsNotRecordable() {
            assertThatThrownBy(() -> TourProgress.start(1L, 7L, "console", 1, TourStatus.NOT_STARTED, null, FIRST))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("같은 버전 병합")
    class SameVersion {

        @Test
        @DisplayName("진행 중끼리는 단계가 큰 쪽이다 — 작은 단계 요청은 그대로(같은 인스턴스)")
        void inProgressKeepsMaxStep() {
            TourProgress progress = stored(1, TourStatus.IN_PROGRESS, 3);

            TourProgress forward = progress.merge(1, TourStatus.IN_PROGRESS, 5, LATER);
            assertThat(forward.currentStep()).isEqualTo(5);
            assertThat(forward.status()).isEqualTo(TourStatus.IN_PROGRESS);
            assertThat(forward.finishedAt()).isNull();
            assertThat(forward.version()).isEqualTo(3L);

            assertThat(progress.merge(1, TourStatus.IN_PROGRESS, 1, LATER)).isSameAs(progress);
            assertThat(progress.merge(1, TourStatus.IN_PROGRESS, 3, LATER)).isSameAs(progress);
            assertThat(progress.merge(1, TourStatus.IN_PROGRESS, null, LATER)).isSameAs(progress);
        }

        @Test
        @DisplayName("저장 단계가 없으면 요청 단계를 받는다")
        void nullStoredStepTakesRequest() {
            assertThat(stored(1, TourStatus.IN_PROGRESS, null).merge(1, TourStatus.IN_PROGRESS, 2, LATER).currentStep()).isEqualTo(2);
        }

        @ParameterizedTest(name = "IN_PROGRESS → {0}")
        @CsvSource({"COMPLETED", "SKIPPED"})
        @DisplayName("진행 중 → 완료 · 건너뜀은 그 순간을 끝난 시각으로 남기고 단계는 큰 쪽이다")
        void inProgressFinishes(TourStatus finished) {
            TourProgress merged = stored(1, TourStatus.IN_PROGRESS, 4).merge(1, finished, 2, LATER);

            assertThat(merged.status()).isEqualTo(finished);
            assertThat(merged.finishedAt()).isEqualTo(LATER);
            assertThat(merged.currentStep()).isEqualTo(4);
        }

        @ParameterizedTest(name = "{0} 에 진행 중 요청")
        @CsvSource({"COMPLETED", "SKIPPED"})
        @DisplayName("끝난 기록에 진행 중이 오면 아무것도 바꾸지 않는다")
        void finishedIgnoresInProgress(TourStatus finished) {
            TourProgress progress = stored(1, finished, 2);

            assertThat(progress.merge(1, TourStatus.IN_PROGRESS, 9, LATER)).isSameAs(progress);
        }

        @Test
        @DisplayName("건너뜀 → 완료는 허용하고 끝난 시각을 다시 남긴다")
        void skippedToCompleted() {
            TourProgress merged = stored(1, TourStatus.SKIPPED, 1).merge(1, TourStatus.COMPLETED, 6, LATER);

            assertThat(merged.status()).isEqualTo(TourStatus.COMPLETED);
            assertThat(merged.finishedAt()).isEqualTo(LATER);
            assertThat(merged.currentStep()).isEqualTo(6);
        }

        @Test
        @DisplayName("완료 → 건너뜀은 아무것도 바꾸지 않는다")
        void completedIgnoresSkipped() {
            TourProgress progress = stored(1, TourStatus.COMPLETED, 6);

            assertThat(progress.merge(1, TourStatus.SKIPPED, 6, LATER)).isSameAs(progress);
        }

        @ParameterizedTest(name = "{0} 다시 보냄")
        @CsvSource({"COMPLETED", "SKIPPED"})
        @DisplayName("같은 끝난 상태를 다시 보내면 그대로다 — 끝난 시각이 바뀌지 않는다(멱등)")
        void sameFinishedIsIdempotent(TourStatus finished) {
            TourProgress progress = stored(1, finished, 2);

            assertThat(progress.merge(1, finished, 2, LATER)).isSameAs(progress);
        }
    }

    @Nested
    @DisplayName("버전")
    class Versions {

        @Test
        @DisplayName("요청 버전이 더 크면 요청으로 교체한다 — 단계는 요청 값, 끝난 시각은 다시 계산")
        void newerVersionReplaces() {
            TourProgress completed = stored(1, TourStatus.COMPLETED, 6);

            TourProgress replaced = completed.merge(2, TourStatus.IN_PROGRESS, 0, LATER);
            assertThat(replaced.guideVersion()).isEqualTo(2);
            assertThat(replaced.status()).isEqualTo(TourStatus.IN_PROGRESS);
            assertThat(replaced.currentStep()).isZero();
            assertThat(replaced.finishedAt()).isNull();
            assertThat(replaced.id()).isEqualTo(10L);
            assertThat(replaced.version()).isEqualTo(3L);

            TourProgress skipped = stored(1, TourStatus.IN_PROGRESS, 4).merge(2, TourStatus.SKIPPED, null, LATER);
            assertThat(skipped.finishedAt()).isEqualTo(LATER);
            assertThat(skipped.currentStep()).isNull();
        }

        @Test
        @DisplayName("교체는 버전 순서를 보지 않는다 — 더 작은 버전으로도 같은 행 · 낙관 락 버전을 이어 받아 바꾼다(설정 롤백). 미시작 · 행 없는 기록은 거절")
        void replaceIgnoresVersionOrder() {
            TourProgress rolledBack = stored(3, TourStatus.COMPLETED, 6).replace(2, TourStatus.SKIPPED, null, LATER);

            assertThat(rolledBack.guideVersion()).isEqualTo(2);
            assertThat(rolledBack.status()).isEqualTo(TourStatus.SKIPPED);
            assertThat(rolledBack.finishedAt()).isEqualTo(LATER);
            assertThat(rolledBack.id()).isEqualTo(10L);
            assertThat(rolledBack.version()).isEqualTo(3L);
            assertThatThrownBy(() -> stored(3, TourStatus.COMPLETED, 6).replace(2, TourStatus.NOT_STARTED, null, LATER))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TourProgress.notStarted(7L, "console", 2).replace(2, TourStatus.IN_PROGRESS, 0, LATER))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("요청 버전이 저장 버전보다 작으면 합치지 않는다 — 호출자가 409 로 먼저 거절한다")
        void olderVersionIsRejected() {
            assertThatThrownBy(() -> stored(2, TourStatus.IN_PROGRESS, 1).merge(1, TourStatus.COMPLETED, 1, LATER))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("설정 버전과 다른 기록 · 행 없는 기록은 설정 버전의 미시작으로 보인다")
        void viewAtConfiguredVersion() {
            TourProgress progress = stored(1, TourStatus.COMPLETED, 6);

            assertThat(progress.viewAt(1)).isSameAs(progress);
            TourProgress outdated = progress.viewAt(2);
            assertThat(outdated.status()).isEqualTo(TourStatus.NOT_STARTED);
            assertThat(outdated.guideVersion()).isEqualTo(2);
            assertThat(outdated.currentStep()).isNull();
            assertThat(outdated.finishedAt()).isNull();
            assertThat(outdated.stored()).isFalse();
        }
    }

    @Test
    @DisplayName("미시작은 행이 없고, 행이 있으면 미시작일 수 없다. 미시작 요청은 병합이 아니다")
    void notStartedInvariants() {
        assertThatThrownBy(() -> new TourProgress(10L, 7L, "console", 1, TourStatus.NOT_STARTED, null, null, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TourProgress(null, 7L, "console", 1, TourStatus.IN_PROGRESS, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stored(1, TourStatus.IN_PROGRESS, 1).merge(1, TourStatus.NOT_STARTED, null, LATER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TourProgress.notStarted(7L, "console", 1).merge(1, TourStatus.IN_PROGRESS, 1, LATER))
            .isInstanceOf(IllegalStateException.class);
    }
}
