package com.commercefoundry.domainlayer.productdraft.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
}
