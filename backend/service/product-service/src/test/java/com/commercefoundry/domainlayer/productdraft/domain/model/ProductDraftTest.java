package com.commercefoundry.domainlayer.productdraft.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 초안 상태 — 보관 기간이 지난 진행 중 초안은 만료로 보이고, 거절은 끝나지 않은 초안만 된다. 누락 입력은 운영자 입력으로 계산한다. */
class ProductDraftTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final ProductDraftFacts EMPTY_FACTS = new ProductDraftFacts(null, null, null, null, null, null, null, null, null, null);

    private static ProductDraft draft(ProductDraftStatus status, Instant expiresAt) {
        return new ProductDraft(1L, status, 100L, EMPTY_FACTS, List.of(), null, null, expiresAt, NOW.minusSeconds(60));
    }

    @ParameterizedTest
    @EnumSource(value = ProductDraftStatus.class, names = {"REQUESTED", "GENERATING", "READY", "FAILED"})
    @DisplayName("끝나지 않은 초안은 만료 시각부터 EXPIRED 로 보인다 — 직전까지는 저장 상태 그대로다")
    void openDraftExpires(ProductDraftStatus status) {
        assertThat(draft(status, NOW).statusAt(NOW)).isEqualTo(ProductDraftStatus.EXPIRED);
        assertThat(draft(status, NOW.plusSeconds(1)).statusAt(NOW)).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = ProductDraftStatus.class, names = {"APPLIED", "REJECTED"})
    @DisplayName("끝난 초안은 기간이 지나도 저장 상태 그대로다")
    void closedDraftKeepsStatus(ProductDraftStatus status) {
        assertThat(draft(status, NOW.minusSeconds(1)).statusAt(NOW)).isEqualTo(status);
    }

    @Test
    @DisplayName("거절하면 REJECTED 와 거절한 어드민 · 시각이 남고 나머지 칸은 그대로다")
    void reject() {
        ProductDraft rejected = draft(ProductDraftStatus.READY, NOW.plusSeconds(60)).reject(200L, NOW);

        assertThat(rejected.status()).isEqualTo(ProductDraftStatus.REJECTED);
        assertThat(rejected.rejectedByMemberId()).isEqualTo(200L);
        assertThat(rejected.rejectedAt()).isEqualTo(NOW);
        assertThat(rejected.requestedByMemberId()).isEqualTo(100L);
        assertThat(rejected.createdAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("만료 · 적용 초안은 domain 이 거절을 막는다")
    void rejectGuard() {
        assertThatThrownBy(() -> draft(ProductDraftStatus.READY, NOW).reject(200L, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft(ProductDraftStatus.APPLIED, NOW.plusSeconds(60)).reject(200L, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("누락 입력은 상품명 → 카테고리 → 대표 이미지 순이고, 채우면 빠진다 — 갤러리 이미지만으로는 대표가 채워지지 않는다")
    void missingRequiredInputs() {
        ProductDraft empty = ProductDraft.request(1L, 100L, EMPTY_FACTS, List.of(new ProductDraftImage("https://cdn/a.jpg", ProductDraftImageType.GALLERY, null, null)), NOW);
        assertThat(empty.missingRequiredInputs()).containsExactly(
            ProductDraftRequiredInput.PRODUCT_NAME, ProductDraftRequiredInput.CATEGORY, ProductDraftRequiredInput.THUMBNAIL_IMAGE
        );

        ProductDraftFacts facts = new ProductDraftFacts("린넨 저고리", 12L, null, null, null, null, null, null, null, null);
        ProductDraft filled = ProductDraft.request(1L, 100L, facts, List.of(new ProductDraftImage("https://cdn/a.jpg", ProductDraftImageType.THUMBNAIL, null, null)), NOW);
        assertThat(filled.missingRequiredInputs()).isEmpty();
    }

    @Nested
    @DisplayName("생성 회차 (#359 PR-B1)")
    class Generation {

        private static final String HASH = "a".repeat(64);

        @Test
        @DisplayName("claim — REQUESTED g=0 → REQUESTED g=1 · 작업 없음, 시작한 어드민 · 시각 · hash 를 남기고 나머지 칸은 그대로다")
        void claim() {
            ProductDraft claimed = draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60)).claimGeneration(200L, HASH, NOW);

            assertThat(claimed.status()).isEqualTo(ProductDraftStatus.REQUESTED);
            assertThat(claimed.generation()).isEqualTo(new ProductDraftGeneration(1, 200L, NOW, HASH, null));
            assertThat(claimed.awaitingGenerationJob()).isTrue();
            assertThat(claimed.requestedByMemberId()).isEqualTo(100L);
            assertThat(claimed.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        }

        @ParameterizedTest
        @EnumSource(value = ProductDraftStatus.class, names = {"GENERATING", "READY", "FAILED", "APPLIED", "REJECTED", "EXPIRED"})
        @DisplayName("claim 거절 — 생성 대기가 아닌 초안(생성 중 포함)")
        void claimRefusedByStatus(ProductDraftStatus status) {
            assertThatThrownBy(() -> draft(status, NOW.plusSeconds(60)).claimGeneration(200L, HASH, NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("claim 거절 — 만료 · 이미 시작한 회차 · 원본을 고정하지 않은 이미지")
        void claimRefusedByRules() {
            assertThatThrownBy(() -> draft(ProductDraftStatus.REQUESTED, NOW).claimGeneration(200L, HASH, NOW)).isInstanceOf(IllegalStateException.class);

            ProductDraft claimed = draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60)).claimGeneration(200L, HASH, NOW);
            assertThatThrownBy(() -> claimed.claimGeneration(300L, HASH, NOW)).isInstanceOf(IllegalStateException.class);

            ProductDraft unpinned = ProductDraft.request(1L, 100L, EMPTY_FACTS,
                List.of(new ProductDraftImage("https://cdn/a.jpg", ProductDraftImageType.THUMBNAIL, null, null)), NOW.plusSeconds(60));
            assertThat(unpinned.imagesPinned()).isFalse();
            assertThatThrownBy(() -> unpinned.claimGeneration(200L, HASH, NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("bindJob — REQUESTED g=1 작업 없음 → GENERATING 작업 붙음. 시작 기록은 그대로다")
        void bind() {
            ProductDraft bound = draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60)).claimGeneration(200L, HASH, NOW).bindJob(1L, 1, HASH, 9L);

            assertThat(bound.status()).isEqualTo(ProductDraftStatus.GENERATING);
            assertThat(bound.generation()).isEqualTo(new ProductDraftGeneration(1, 200L, NOW, HASH, 9L));
            assertThat(bound.awaitingGenerationJob()).isFalse();
        }

        @Test
        @DisplayName("bindJob 거절 — 시작 전 · 이미 작업이 붙음 · 등록 응답의 초안 · 회차 · hash 불일치 · 잘못된 작업 아이디")
        void bindRefused() {
            ProductDraft unclaimed = draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60));
            assertThatThrownBy(() -> unclaimed.bindJob(1L, 1, HASH, 9L)).isInstanceOf(IllegalStateException.class);

            ProductDraft claimed = unclaimed.claimGeneration(200L, HASH, NOW);
            ProductDraft bound = claimed.bindJob(1L, 1, HASH, 9L);
            assertThatThrownBy(() -> bound.bindJob(1L, 1, HASH, 9L)).isInstanceOf(IllegalStateException.class);

            assertThatThrownBy(() -> claimed.bindJob(2L, 1, HASH, 9L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> claimed.bindJob(1L, 2, HASH, 9L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> claimed.bindJob(1L, 1, "b".repeat(64), 9L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> claimed.bindJob(1L, 1, HASH, 0L)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("거절해도 생성 회차 기록은 남는다")
        void rejectKeepsGeneration() {
            ProductDraft claimed = draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60)).claimGeneration(200L, HASH, NOW);

            assertThat(claimed.reject(300L, NOW).generation()).isEqualTo(claimed.generation());
        }

        @Test
        @DisplayName("회차 기록 — 0 이면 모두 비고, 1~3 이면 시작 기록이 있어야 한다. 범위 밖은 만들 수 없다")
        void generationInvariant() {
            assertThatThrownBy(() -> new ProductDraftGeneration(0, 200L, null, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProductDraftGeneration(1, null, NOW, HASH, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ProductDraftGeneration(4, 200L, NOW, HASH, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProductDraftGeneration(-1, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("재생성 (#359 B3)")
    class Regeneration {

        private static final String HASH = "a".repeat(64);

        private ProductDraft finished(ProductDraftStatus status, int generationNo, String failureCode, Instant expiresAt) {
            return new ProductDraft(1L, status, 100L, EMPTY_FACTS, List.of(), null, null, expiresAt, NOW.minusSeconds(60),
                new ProductDraftGeneration(generationNo, 200L, NOW.minusSeconds(30), HASH, 9L), null, failureCode);
        }

        @Test
        @DisplayName("검토 대기 g=1 → 생성 대기 g=2 · 작업 없음 · 새 어드민 · 시각, 이전 결과 · 실패 사유는 지운다")
        void claimNextGeneration() {
            ProductDraft failed = finished(ProductDraftStatus.FAILED, 1, "PRODUCT_DRAFT_AI_FAILED", NOW.plusSeconds(60));

            ProductDraft claimed = failed.claimGeneration(300L, HASH, NOW);

            assertThat(claimed.status()).isEqualTo(ProductDraftStatus.REQUESTED);
            assertThat(claimed.generation()).isEqualTo(new ProductDraftGeneration(2, 300L, NOW, HASH, null));
            assertThat(claimed.failureCode()).isNull();
            assertThat(claimed.candidate()).isNull();
            assertThat(claimed.awaitingGenerationJob()).isTrue();
            assertThat(claimed.bindJob(1L, 2, HASH, 10L).status()).isEqualTo(ProductDraftStatus.GENERATING);
        }

        @Test
        @DisplayName("다시 생성할 수 있음 — 기간 안의 검토 대기 · 다시 만들 수 있는 실패이고 회차가 3 보다 작을 때만")
        void availability() {
            assertThat(finished(ProductDraftStatus.READY, 1, null, NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isTrue();
            assertThat(finished(ProductDraftStatus.READY, 2, null, NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isTrue();
            assertThat(finished(ProductDraftStatus.FAILED, 2, ProductDraft.GENERATION_CANCELLED, NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isTrue();

            assertThat(finished(ProductDraftStatus.READY, 3, null, NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isFalse();
            assertThat(finished(ProductDraftStatus.FAILED, 3, "PRODUCT_DRAFT_AI_FAILED", NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isFalse();
            assertThat(finished(ProductDraftStatus.READY, 1, null, NOW).regenerationAvailableAt(NOW)).isFalse();
            assertThat(finished(ProductDraftStatus.GENERATING, 1, null, NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isFalse();
            assertThat(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isFalse();
            assertThat(draft(ProductDraftStatus.READY, NOW.plusSeconds(60)).regenerationAvailableAt(NOW)).isFalse();
        }

        @ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"PRODUCT_DRAFT_IMAGE_CHANGED", "PRODUCT_DRAFT_INPUT_EXPIRED", "GENERATION_RESULT_EXPIRED",
            "PRODUCT_DRAFT_INPUT_REJECTED", "PRODUCT_DRAFT_INPUT_MISMATCH", "PRODUCT_DRAFT_INPUT_INVALID", "PRODUCT_DRAFT_IMAGES_TOO_LARGE"})
        @DisplayName("새 초안이 필요한 실패는 다시 생성할 수 없고 claim 도 막는다")
        void newDraftRequired(String failureCode) {
            ProductDraft failed = finished(ProductDraftStatus.FAILED, 1, failureCode, NOW.plusSeconds(60));

            assertThat(failed.requiresNewDraft()).isTrue();
            assertThat(failed.regenerationAvailableAt(NOW)).isFalse();
            assertThatThrownBy(() -> failed.claimGeneration(300L, HASH, NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("3회차를 다 쓴 초안 · 만료 초안 · 생성 중 초안은 claim 을 막는다")
        void claimRefused() {
            assertThatThrownBy(() -> finished(ProductDraftStatus.READY, 3, null, NOW.plusSeconds(60)).claimGeneration(300L, HASH, NOW))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> finished(ProductDraftStatus.READY, 1, null, NOW).claimGeneration(300L, HASH, NOW))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> finished(ProductDraftStatus.GENERATING, 1, null, NOW.plusSeconds(60)).claimGeneration(300L, HASH, NOW))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
