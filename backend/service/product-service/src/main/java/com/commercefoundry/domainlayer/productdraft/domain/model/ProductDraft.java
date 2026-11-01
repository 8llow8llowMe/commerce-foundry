package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * AI 상품 등록 초안 (#359). 운영자 입력(사실 · 이미지)을 담고, 검토한 값만 상품 등록으로 적용한다 — 초안이 상품을 직접 바꾸거나 공개하지 않는다.
 * 상태는 이 모델의 메서드로만 바꾼다 ({@code architecture-guide.md} 상태 전이 규칙).
 *
 * <p>보관 기간({@code expiresAt})이 지난 진행 중 초안은 저장 값과 상관없이 {@code EXPIRED} 로 본다 — {@link #statusAt}. 정리 작업은 두지 않는다.
 *
 * @param requestedByMemberId 초안을 만든 어드민 (토큰의 memberId)
 * @param rejectedByMemberId 거절한 어드민. 거절 전에는 null
 * @param rejectedAt 거절 시각. 거절 전에는 null
 * @param createdAt 만든 시각. 저장 전에는 null
 */
public record ProductDraft(
    long id,
    ProductDraftStatus status,
    long requestedByMemberId,
    ProductDraftFacts facts,
    List<ProductDraftImage> images,
    Long rejectedByMemberId,
    Instant rejectedAt,
    Instant expiresAt,
    Instant createdAt
) {

    public ProductDraft {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(expiresAt, "expiresAt");
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** 새 초안 — 생성 대기({@code REQUESTED})에서 출발한다. */
    public static ProductDraft request(long id, long requestedByMemberId, ProductDraftFacts facts, List<ProductDraftImage> images, Instant expiresAt) {
        return new ProductDraft(id, ProductDraftStatus.REQUESTED, requestedByMemberId, facts, images, null, null, expiresAt, null);
    }

    /** {@code now} 기준 상태 — 끝나지 않은 초안이 보관 기간을 넘었으면 {@code EXPIRED} 다. */
    public ProductDraftStatus statusAt(Instant now) {
        if (status.isOpen() && !now.isBefore(expiresAt)) {
            return ProductDraftStatus.EXPIRED;
        }
        return status;
    }

    /**
     * 거절 — 끝나지 않고 기간이 남은 초안만 된다. 판정은 호출한 쪽이 {@link #statusAt} 으로 먼저 한다(이미 거절 · 적용 · 만료는 업무 오류다).
     *
     * @throws IllegalStateException 거절할 수 없는 상태
     */
    public ProductDraft reject(long memberId, Instant now) {
        if (!statusAt(now).isOpen()) {
            throw new IllegalStateException("거절할 수 없는 초안 상태입니다: " + statusAt(now));
        }
        return new ProductDraft(id, ProductDraftStatus.REJECTED, requestedByMemberId, facts, images, memberId, now, expiresAt, createdAt);
    }

    /** 운영자 입력에 아직 없는 등록 필수 입력 — 상품명 → 카테고리 → 대표 이미지 순. 가격 · 옵션 · 재고는 적용 단계에서 받는다. */
    public List<ProductDraftRequiredInput> missingRequiredInputs() {
        List<ProductDraftRequiredInput> missing = new ArrayList<>();
        if (facts.nameHint() == null) {
            missing.add(ProductDraftRequiredInput.PRODUCT_NAME);
        }
        if (facts.categoryId() == null) {
            missing.add(ProductDraftRequiredInput.CATEGORY);
        }
        if (images.stream().noneMatch(image -> image.imageType() == ProductDraftImageType.THUMBNAIL)) {
            missing.add(ProductDraftRequiredInput.THUMBNAIL_IMAGE);
        }
        return missing;
    }
}
