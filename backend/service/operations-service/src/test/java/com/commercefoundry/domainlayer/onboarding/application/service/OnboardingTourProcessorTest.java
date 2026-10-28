package com.commercefoundry.domainlayer.onboarding.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response.OnboardingAdminResponse;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.presenter.OnboardingAdminPresenter;
import com.commercefoundry.domainlayer.onboarding.application.command.TourProgressUpdateCommand;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.TourProgressRepositoryPort;
import com.commercefoundry.domainlayer.onboarding.application.service.processor.OnboardingTourProcessor;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourStatus;
import com.commercefoundry.global.properties.OnboardingProperties;
import com.commercefoundry.global.properties.OnboardingProperties.Guide;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 투어 진행 Processor 의 조회 합성 · 기록 · 재실행과, Facade 의 충돌 재시도를 메모리 저장소로 본다. 실제 버전 비교 · 유니크 키는 {@code OnboardingTourProgressRepositoryTest} 가 본다.
 */
class OnboardingTourProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");
    private static final long MEMBER = 7L;
    private static final long OTHER_MEMBER = 8L;

    private final MemoryPort port = new MemoryPort();
    private final OnboardingProperties properties = new OnboardingProperties(List.of(new Guide("console", 2), new Guide("stocks", 1)));
    private final OnboardingTourProcessor processor =
        new OnboardingTourProcessor(port, properties, new SnowflakeIdGenerator(0, 1), Clock.fixed(NOW, ZoneOffset.UTC));
    private final OnboardingAdminFacade facade = new OnboardingAdminFacade(processor, new OnboardingAdminPresenter());

    private static TourProgressUpdateCommand command(String key, int version, TourStatus status, Integer step) {
        return new TourProgressUpdateCommand(key, version, status, step);
    }

    @Nested
    @DisplayName("조회")
    class GetMine {

        @Test
        @DisplayName("설정 순서로 전부 낸다 — 기록이 없거나 설정 버전과 다르면 미시작, 다른 운영자 기록은 섞이지 않는다")
        void synthesizesNotStarted() {
            port.put(new TourProgress(1L, MEMBER, "stocks", 1, TourStatus.COMPLETED, 3, NOW, 0L));
            port.put(new TourProgress(2L, MEMBER, "console", 1, TourStatus.COMPLETED, 9, NOW, 0L));
            port.put(new TourProgress(3L, MEMBER, "retired", 1, TourStatus.SKIPPED, null, NOW, 0L));
            port.put(new TourProgress(4L, OTHER_MEMBER, "console", 2, TourStatus.IN_PROGRESS, 1, null, 0L));

            OnboardingAdminResponse response = facade.getMine(MEMBER);

            assertThat(response.tasks()).isEmpty();
            assertThat(response.tours()).extracting(OnboardingTourItem::guideKey).containsExactly("console", "stocks");
            OnboardingTourItem console = response.tours().get(0);
            assertThat(console.status().code()).isEqualTo("NOT_STARTED");
            assertThat(console.guideVersion()).isEqualTo(2);
            assertThat(console.currentStep()).isNull();
            OnboardingTourItem stocks = response.tours().get(1);
            assertThat(stocks.status().code()).isEqualTo("COMPLETED");
            assertThat(stocks.finishedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("기록")
    class Update {

        @Test
        @DisplayName("첫 기록은 행을 만들고, 같은 본문을 다시 보내면 결과가 같다(멱등 — 다시 저장하지 않는다)")
        void firstRecordAndIdempotent() {
            TourProgress first = processor.update(MEMBER, command("console", 2, TourStatus.COMPLETED, 4));
            TourProgress again = processor.update(MEMBER, command("console", 2, TourStatus.COMPLETED, 4));

            assertThat(first.status()).isEqualTo(TourStatus.COMPLETED);
            assertThat(first.finishedAt()).isEqualTo(NOW);
            assertThat(again).isEqualTo(first);
            assertThat(port.saves.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("허용 목록에 없는 키는 404 · ONBOARDING_001 이고 저장하지 않는다")
        void unknownGuide() {
            assertThatThrownBy(() -> processor.update(MEMBER, command("unknown", 1, TourStatus.IN_PROGRESS, 0)))
                .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.GUIDE_NOT_FOUND));
            assertThatThrownBy(() -> processor.update(MEMBER, command("unknown", 1, TourStatus.NOT_STARTED, null)))
                .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.GUIDE_NOT_FOUND));
            assertThat(port.rows).isEmpty();
        }

        @Test
        @DisplayName("설정 버전보다 큰 버전은 400 · ONBOARDING_103")
        void unreleasedVersion() {
            assertThatThrownBy(() -> processor.update(MEMBER, command("console", 3, TourStatus.IN_PROGRESS, 0)))
                .isInstanceOfSatisfying(OnboardingException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.UNRELEASED_GUIDE_VERSION));
        }

        @Test
        @DisplayName("설정 버전보다 오래된 요청(옛 번들)은 409 · ONBOARDING_020 이고 기록은 그대로다")
        void staleVersion() {
            port.put(new TourProgress(1L, MEMBER, "console", 2, TourStatus.IN_PROGRESS, 1, null, 0L));

            assertThatThrownBy(() -> processor.update(MEMBER, command("console", 1, TourStatus.COMPLETED, 5)))
                .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.STALE_GUIDE_VERSION));
            assertThat(port.find(MEMBER, "console").orElseThrow().status()).isEqualTo(TourStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("저장 행이 없어도 · 저장 버전이 요청과 같아도, 설정 버전보다 오래된 요청은 409 · ONBOARDING_020 이고 저장하지 않는다")
        void staleVersionIsRejectedRegardlessOfStoredRow() {
            assertThatThrownBy(() -> processor.update(MEMBER, command("console", 1, TourStatus.IN_PROGRESS, 0)))
                .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.STALE_GUIDE_VERSION));
            assertThat(port.rows).isEmpty();

            port.put(new TourProgress(1L, MEMBER, "console", 1, TourStatus.IN_PROGRESS, 1, null, 0L));
            assertThatThrownBy(() -> processor.update(MEMBER, command("console", 1, TourStatus.COMPLETED, 5)))
                .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.STALE_GUIDE_VERSION));
            assertThat(port.find(MEMBER, "console").orElseThrow().status()).isEqualTo(TourStatus.IN_PROGRESS);
            assertThat(port.saveAttempts.get()).isZero();
        }

        @Test
        @DisplayName("설정이 롤백돼 저장 버전이 설정 버전보다 크면 그 행을 없는 것으로 보고 요청으로 교체한다 — 같은 행 · 낙관 락 버전을 이어 받는다")
        void rolledBackConfigReplacesNewerRow() {
            port.put(new TourProgress(1L, MEMBER, "console", 3, TourStatus.COMPLETED, 9, NOW.minusSeconds(60), 4L));
            assertThat(processor.getMine(MEMBER).get(0).status()).isEqualTo(TourStatus.NOT_STARTED);

            TourProgress replaced = processor.update(MEMBER, command("console", 2, TourStatus.IN_PROGRESS, 1));

            assertThat(replaced.guideVersion()).isEqualTo(2);
            assertThat(replaced.status()).isEqualTo(TourStatus.IN_PROGRESS);
            assertThat(replaced.currentStep()).isEqualTo(1);
            assertThat(replaced.finishedAt()).isNull();
            assertThat(replaced.id()).isEqualTo(1L);
            assertThat(replaced.version()).isEqualTo(5L);
            assertThat(processor.getMine(MEMBER).get(0).status()).isEqualTo(TourStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("롤백 교체 뒤에는 같은 버전이라 병합한다 — 끝난 기록에 진행 중이 와도 그대로다")
        void afterRollbackReplaceMerges() {
            port.put(new TourProgress(1L, MEMBER, "console", 3, TourStatus.IN_PROGRESS, 1, null, 0L));

            processor.update(MEMBER, command("console", 2, TourStatus.COMPLETED, 4));
            TourProgress merged = processor.update(MEMBER, command("console", 2, TourStatus.IN_PROGRESS, 7));

            assertThat(merged.status()).isEqualTo(TourStatus.COMPLETED);
            assertThat(merged.currentStep()).isEqualTo(4);
            assertThat(port.saves.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("요청 · 저장 · 설정 버전이 모두 같으면 병합한다 — 진행 중끼리는 큰 단계")
        void sameVersionMerges() {
            port.put(new TourProgress(1L, MEMBER, "console", 2, TourStatus.IN_PROGRESS, 5, null, 0L));

            TourProgress merged = processor.update(MEMBER, command("console", 2, TourStatus.IN_PROGRESS, 3));

            assertThat(merged.currentStep()).isEqualTo(5);
            assertThat(port.saves.get()).isZero();
        }

        @Test
        @DisplayName("재실행은 버전과 관계없이 지운다 — 배포 전 · 옛 버전 요청도 지우고 설정 버전의 미시작을 낸다")
        void resetIgnoresVersion() {
            port.put(new TourProgress(1L, MEMBER, "console", 2, TourStatus.COMPLETED, 4, NOW, 0L));
            TourProgress reset = processor.update(MEMBER, command("console", 9, TourStatus.NOT_STARTED, null));
            assertThat(reset.guideVersion()).isEqualTo(2);
            assertThat(port.find(MEMBER, "console")).isEmpty();

            port.put(new TourProgress(2L, MEMBER, "console", 3, TourStatus.COMPLETED, 4, NOW, 0L));
            processor.update(MEMBER, command("console", 1, TourStatus.NOT_STARTED, null));
            assertThat(port.find(MEMBER, "console")).isEmpty();
        }

        @Test
        @DisplayName("옛 버전 기록에 새 버전 요청이 오면 교체한다")
        void newerVersionReplaces() {
            port.put(new TourProgress(1L, MEMBER, "console", 1, TourStatus.COMPLETED, 9, NOW.minusSeconds(60), 0L));

            TourProgress replaced = processor.update(MEMBER, command("console", 2, TourStatus.IN_PROGRESS, 0));

            assertThat(replaced.guideVersion()).isEqualTo(2);
            assertThat(replaced.status()).isEqualTo(TourStatus.IN_PROGRESS);
            assertThat(replaced.currentStep()).isZero();
            assertThat(replaced.finishedAt()).isNull();
        }

        @Test
        @DisplayName("재실행(NOT_STARTED)은 그 운영자의 그 투어 기록만 지우고 설정 버전의 미시작을 낸다. 없으면 그대로다")
        void resetDeletes() {
            port.put(new TourProgress(1L, MEMBER, "console", 2, TourStatus.COMPLETED, 4, NOW, 0L));
            port.put(new TourProgress(2L, MEMBER, "stocks", 1, TourStatus.SKIPPED, null, NOW, 0L));
            port.put(new TourProgress(3L, OTHER_MEMBER, "console", 2, TourStatus.COMPLETED, 4, NOW, 0L));

            TourProgress reset = processor.update(MEMBER, command("console", 1, TourStatus.NOT_STARTED, 3));
            TourProgress resetAgain = processor.update(MEMBER, command("console", 1, TourStatus.NOT_STARTED, null));

            assertThat(reset.status()).isEqualTo(TourStatus.NOT_STARTED);
            assertThat(reset.guideVersion()).isEqualTo(2);
            assertThat(reset.currentStep()).isNull();
            assertThat(resetAgain).isEqualTo(reset);
            assertThat(port.find(MEMBER, "console")).isEmpty();
            assertThat(port.find(MEMBER, "stocks")).isPresent();
            assertThat(port.find(OTHER_MEMBER, "console")).isPresent();
        }
    }

    @Nested
    @DisplayName("동시 갱신 재시도 (Facade)")
    class Retry {

        @Test
        @DisplayName("한 번 지면 새로 읽어 다시 합친다")
        void retriesAndMerges() {
            port.failSaves(1);

            OnboardingTourItem item = facade.updateTour(MEMBER, command("console", 2, TourStatus.IN_PROGRESS, 2));

            assertThat(item.status().code()).isEqualTo("IN_PROGRESS");
            assertThat(item.currentStep()).isEqualTo(2);
            assertThat(port.saveAttempts.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("진 사이 다른 탭이 완료로 기록했으면, 다시 읽어 합친 결과는 완료로 남는다 — 진행 중으로 덮어쓰지 않는다")
        void retryKeepsWinnerCompleted() {
            TourProgress winner = new TourProgress(99L, MEMBER, "console", 2, TourStatus.COMPLETED, 6, NOW.minusSeconds(5), 0L);
            port.failSaves(1, () -> port.put(winner));

            OnboardingTourItem item = facade.updateTour(MEMBER, command("console", 2, TourStatus.IN_PROGRESS, 2));

            assertThat(item.status().code()).isEqualTo("COMPLETED");
            assertThat(item.currentStep()).isEqualTo(6);
            assertThat(item.finishedAt()).isEqualTo(NOW.minusSeconds(5));
            assertThat(port.find(MEMBER, "console").orElseThrow()).isEqualTo(winner);
            assertThat(port.saveAttempts.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("3번 모두 지면 409 · ONBOARDING_021 이다 — 4번째는 시도하지 않는다")
        void givesUpAfterThreeAttempts() {
            port.failSaves(Integer.MAX_VALUE);

            assertThatThrownBy(() -> facade.updateTour(MEMBER, command("console", 2, TourStatus.IN_PROGRESS, 2)))
                .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.CONCURRENT_UPDATE));
            assertThat(port.saveAttempts.get()).isEqualTo(OnboardingAdminFacade.UPDATE_ATTEMPTS);
        }

        @Test
        @DisplayName("충돌이 아닌 오류는 다시 시도하지 않는다")
        void doesNotRetryOtherErrors() {
            assertThatThrownBy(() -> facade.updateTour(MEMBER, command("console", 3, TourStatus.IN_PROGRESS, 2)))
                .isInstanceOfSatisfying(OnboardingException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.UNRELEASED_GUIDE_VERSION));
            assertThat(port.saveAttempts.get()).isZero();
        }
    }

    /** 메모리 저장소. 지정한 횟수만큼 저장을 충돌로 실패시킨다 — 실패 직전에 다른 탭이 이긴 기록을 흉내 낼 수 있다. */
    private static final class MemoryPort implements TourProgressRepositoryPort {

        private final Map<String, TourProgress> rows = new LinkedHashMap<>();
        private final AtomicInteger saves = new AtomicInteger();
        private final AtomicInteger saveAttempts = new AtomicInteger();
        private int failuresLeft;
        private Runnable onFailure = () -> { };

        void put(TourProgress progress) {
            rows.put(progress.memberId() + ":" + progress.guideKey(), progress);
        }

        void failSaves(int count) {
            failuresLeft = count;
        }

        void failSaves(int count, Runnable winnerWrites) {
            failuresLeft = count;
            onFailure = winnerWrites;
        }

        @Override
        public List<TourProgress> findAllByMember(long memberId) {
            return new ArrayList<>(rows.values().stream().filter(progress -> progress.memberId() == memberId).toList());
        }

        @Override
        public Optional<TourProgress> find(long memberId, String guideKey) {
            return Optional.ofNullable(rows.get(memberId + ":" + guideKey));
        }

        @Override
        public TourProgress save(TourProgress progress) {
            saveAttempts.incrementAndGet();
            if (failuresLeft > 0) {
                failuresLeft--;
                onFailure.run();
                throw new OnboardingException(OnboardingErrorCode.CONCURRENT_UPDATE);
            }
            saves.incrementAndGet();
            TourProgress stored = new TourProgress(progress.id(), progress.memberId(), progress.guideKey(), progress.guideVersion(), progress.status(),
                progress.currentStep(), progress.finishedAt(), progress.version() == null ? 0L : progress.version() + 1);
            put(stored);
            return stored;
        }

        @Override
        public int delete(long memberId, String guideKey) {
            return rows.remove(memberId + ":" + guideKey) == null ? 0 : 1;
        }
    }
}
