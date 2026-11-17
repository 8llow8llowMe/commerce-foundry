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
 * @param generation AI 생성 회차(#359 PR-B1). 시작 전에는 {@link ProductDraftGeneration#NONE}
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
    Instant createdAt,
    ProductDraftGeneration generation
) {

    public ProductDraft {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(expiresAt, "expiresAt");
        images = images == null ? List.of() : List.copyOf(images);
        generation = generation == null ? ProductDraftGeneration.NONE : generation;
    }

    /** 생성을 시작하지 않은 초안 — 생성 회차 칸이 생기기 전 모양이다. */
    public ProductDraft(long id, ProductDraftStatus status, long requestedByMemberId, ProductDraftFacts facts, List<ProductDraftImage> images,
        Long rejectedByMemberId, Instant rejectedAt, Instant expiresAt, Instant createdAt) {
        this(id, status, requestedByMemberId, facts, images, rejectedByMemberId, rejectedAt, expiresAt, createdAt, ProductDraftGeneration.NONE);
    }

    /** 새 초안 — 생성 대기({@code REQUESTED})에서 출발한다. */
    public static ProductDraft request(long id, long requestedByMemberId, ProductDraftFacts facts, List<ProductDraftImage> images, Instant expiresAt) {
        return new ProductDraft(id, ProductDraftStatus.REQUESTED, requestedByMemberId, facts, images, null, null, expiresAt, null);
    }

    /** 모든 이미지의 원본(관리 키 · ETag)을 고정했는가 — 이미지가 없으면 참이다. PR-2a 이전 초안은 거짓일 수 있다. */
    public boolean imagesPinned() {
        return images.stream().allMatch(image -> image.source() != null);
    }

    /** 회차를 시작했지만 작업 등록을 아직 반영하지 못한 초안 — 같은 값으로 다시 등록하면 된다(operations 영수증 재생). */
    public boolean awaitingGenerationJob() {
        return status == ProductDraftStatus.REQUESTED && generation.claimed() && generation.jobId() == null;
    }

    /**
     * 생성 회차 시작(claim) — 생성 대기({@code REQUESTED}) · 회차 0 · 기간 안 · 이미지 원본 고정 완료인 초안만 된다. 회차가 하나 오르고 요청자 · 시각 · 입력 hash 를 고정한다.
     * 상태는 그대로 {@code REQUESTED} 이고 작업은 등록 응답을 반영할 때({@link #bindJob}) 붙는다. 생성 중({@code GENERATING})에서는 막는다 — 이전 회차가 끝나기 전에
     * 다음 회차를 등록하면 AI 를 두 번 부를 수 있다. 판정은 호출한 쪽이 업무 오류로 먼저 한다.
     *
     * @param claimedAt 시작 시각 — DB 정밀도(마이크로초)로 자른 값
     * @throws IllegalStateException 시작할 수 없는 초안
     */
    public ProductDraft claimGeneration(long actorMemberId, String inputHash, Instant claimedAt) {
        // B1 은 첫 회차만 연다. 재생성(B3)은 READY · FAILED 에서 REQUESTED 로 되돌리며 다음 회차를 여는 분기를 여기 더한다.
        if (statusAt(claimedAt) != ProductDraftStatus.REQUESTED || generation.claimed()) {
            throw new IllegalStateException("생성을 시작할 수 없는 초안 상태입니다: " + statusAt(claimedAt) + ", generationNo=" + generation.generationNo());
        }
        if (!imagesPinned()) {
            throw new IllegalStateException("이미지 원본을 고정하지 않은 초안은 생성할 수 없습니다.");
        }
        Objects.requireNonNull(inputHash, "inputHash");
        return withGeneration(ProductDraftStatus.REQUESTED, generation.next(actorMemberId, claimedAt, inputHash));
    }

    /**
     * 등록한 작업 연결 — 회차를 시작했고 작업이 없는 생성 대기 초안만 생성 중({@code GENERATING})이 된다. 등록 응답의 초안 · 회차 · hash 가 이 초안과 같아야 한다.
     *
     * @throws IllegalStateException 연결할 수 없는 상태
     * @throws IllegalArgumentException 등록 응답이 이 초안 회차와 다름
     */
    public ProductDraft bindJob(long registeredDraftId, int registeredGenerationNo, String registeredInputHash, long jobId) {
        if (!awaitingGenerationJob()) {
            throw new IllegalStateException("작업을 연결할 수 없는 초안 상태입니다: " + status + ", generationNo=" + generation.generationNo());
        }
        if (registeredDraftId != id || registeredGenerationNo != generation.generationNo() || !generation.inputHash().equals(registeredInputHash) || jobId <= 0) {
            throw new IllegalArgumentException("등록 응답이 초안 회차와 다릅니다. draftId=" + id);
        }
        return withGeneration(ProductDraftStatus.GENERATING, generation.bound(jobId));
    }

    private ProductDraft withGeneration(ProductDraftStatus nextStatus, ProductDraftGeneration nextGeneration) {
        return new ProductDraft(id, nextStatus, requestedByMemberId, facts, images, rejectedByMemberId, rejectedAt, expiresAt, createdAt, nextGeneration);
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
        return new ProductDraft(id, ProductDraftStatus.REJECTED, requestedByMemberId, facts, images, memberId, now, expiresAt, createdAt, generation);
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
