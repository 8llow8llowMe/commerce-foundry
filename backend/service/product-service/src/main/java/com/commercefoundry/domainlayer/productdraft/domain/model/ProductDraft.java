package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    ProductDraftGeneration generation,
    ProductDraftCandidate candidate,
    String failureCode
) {

    public static final String GENERATION_CANCELLED = "GENERATION_CANCELLED";
    public static final String GENERATION_RESULT_EXPIRED = "GENERATION_RESULT_EXPIRED";

    /**
     * 같은 입력으로 다시 생성해도 결과가 달라지지 않는 실패 — 새 초안이 필요하다(#359 B3). 이미지가 바뀌었거나 입력 · 결과 보관이 끝났거나 입력 자체가 거절됐다.
     * 그 밖의 실패(AI 오류 · 시간 초과 · 취소 · 한도 초과 등)는 남은 회차로 다시 생성할 수 있다.
     */
    private static final Set<String> NEW_DRAFT_REQUIRED_FAILURES = Set.of(
        "PRODUCT_DRAFT_IMAGE_CHANGED", "PRODUCT_DRAFT_INPUT_EXPIRED", GENERATION_RESULT_EXPIRED,
        "PRODUCT_DRAFT_INPUT_REJECTED", "PRODUCT_DRAFT_INPUT_MISMATCH", "PRODUCT_DRAFT_INPUT_INVALID", "PRODUCT_DRAFT_IMAGES_TOO_LARGE"
    );

    public ProductDraft {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(expiresAt, "expiresAt");
        images = images == null ? List.of() : List.copyOf(images);
        generation = generation == null ? ProductDraftGeneration.NONE : generation;
    }

    public ProductDraft(long id, ProductDraftStatus status, long requestedByMemberId, ProductDraftFacts facts, List<ProductDraftImage> images,
        Long rejectedByMemberId, Instant rejectedAt, Instant expiresAt, Instant createdAt, ProductDraftGeneration generation) {
        this(id, status, requestedByMemberId, facts, images, rejectedByMemberId, rejectedAt, expiresAt, createdAt, generation, null, null);
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

    /** 등록 중 거절됐어도 이미 받은 작업 아이디는 기록할 수 있다. 거절 상태는 그대로 둔다. */
    public boolean canBindGenerationJob() {
        return (status == ProductDraftStatus.REQUESTED || status == ProductDraftStatus.REJECTED) && generation.claimed() && generation.jobId() == null;
    }

    /**
     * 생성 회차 시작(claim) — 기간 안 · 이미지 원본 고정 완료이고, 아래 둘 중 하나인 초안만 된다. 회차가 하나 오르고 요청자 · 시각 · 입력 hash 를 고정한다.
     * <ul>
     *   <li>첫 회차: 생성 대기({@code REQUESTED}) · 회차 0</li>
     *   <li>재생성(#359 B3): {@link #regenerationAvailableAt} — 검토 대기({@code READY}) 또는 다시 생성할 수 있는 실패({@code FAILED}) · 남은 회차 있음.
     *       이전 회차의 제안 · 실패 사유는 지운다</li>
     * </ul>
     * 상태는 {@code REQUESTED} 가 되고 작업은 등록 응답을 반영할 때({@link #bindJob}) 붙는다. 생성 중({@code GENERATING})에서는 막는다 — 이전 회차가 끝나기 전에
     * 다음 회차를 등록하면 AI 를 두 번 부를 수 있다. 판정은 호출한 쪽이 업무 오류로 먼저 한다.
     *
     * @param claimedAt 시작 시각 — DB 정밀도(마이크로초)로 자른 값
     * @throws IllegalStateException 시작할 수 없는 초안
     */
    public ProductDraft claimGeneration(long actorMemberId, String inputHash, Instant claimedAt) {
        boolean first = statusAt(claimedAt) == ProductDraftStatus.REQUESTED && !generation.claimed();
        if (!first && !regenerationAvailableAt(claimedAt)) {
            throw new IllegalStateException("생성을 시작할 수 없는 초안 상태입니다: " + statusAt(claimedAt) + ", generationNo=" + generation.generationNo());
        }
        if (!imagesPinned()) {
            throw new IllegalStateException("이미지 원본을 고정하지 않은 초안은 생성할 수 없습니다.");
        }
        Objects.requireNonNull(inputHash, "inputHash");
        return withGeneration(ProductDraftStatus.REQUESTED, generation.next(actorMemberId, claimedAt, inputHash));
    }

    /**
     * 지금 다시 생성할 수 있는가(#359 B3) — 기간 안의 검토 대기({@code READY}) 또는 다시 생성할 수 있는 실패({@code FAILED})이고 회차가 상한보다 작다.
     * 실패한 회차도 횟수에 든다. 새 초안이 필요한 실패({@link #requiresNewDraft})는 같은 입력으로 다시 생성해도 결과가 같아 막는다.
     */
    public boolean regenerationAvailableAt(Instant now) {
        ProductDraftStatus current = statusAt(now);
        boolean finished = current == ProductDraftStatus.READY || (current == ProductDraftStatus.FAILED && !requiresNewDraft());
        return finished && generation.claimed() && generation.generationNo() < ProductDraftGeneration.MAX_GENERATION_NO;
    }

    /** 실패 사유가 새 초안을 요구하는가 — 이미지 변경 · 입력 · 결과 보관 만료 · 입력 거절. */
    public boolean requiresNewDraft() {
        return failureCode != null && NEW_DRAFT_REQUIRED_FAILURES.contains(failureCode);
    }

    /**
     * 등록한 작업 연결 — 회차를 시작했고 작업이 없는 생성 대기 초안만 생성 중({@code GENERATING})이 된다. 등록 응답의 초안 · 회차 · hash 가 이 초안과 같아야 한다.
     *
     * @throws IllegalStateException 연결할 수 없는 상태
     * @throws IllegalArgumentException 등록 응답이 이 초안 회차와 다름
     */
    public ProductDraft bindJob(long registeredDraftId, int registeredGenerationNo, String registeredInputHash, long jobId) {
        if (!canBindGenerationJob()) {
            throw new IllegalStateException("작업을 연결할 수 없는 초안 상태입니다: " + status + ", generationNo=" + generation.generationNo());
        }
        if (registeredDraftId != id || registeredGenerationNo != generation.generationNo() || !generation.inputHash().equals(registeredInputHash) || jobId <= 0) {
            throw new IllegalArgumentException("등록 응답이 초안 회차와 다릅니다. draftId=" + id);
        }
        return withGeneration(status == ProductDraftStatus.REJECTED ? ProductDraftStatus.REJECTED : ProductDraftStatus.GENERATING,
            generation.bound(jobId));
    }

    private ProductDraft withGeneration(ProductDraftStatus nextStatus, ProductDraftGeneration nextGeneration) {
        return new ProductDraft(id, nextStatus, requestedByMemberId, facts, images, rejectedByMemberId, rejectedAt, expiresAt, createdAt, nextGeneration);
    }

    /** 현재 회차의 확정 결과를 저장한다. 결합값과 상태를 확인한 쓰기 트랜잭션에서만 호출한다. */
    public ProductDraft finishGeneration(Instant now, ProductDraftCandidate candidate, String failureCode) {
        if (statusAt(now) != ProductDraftStatus.GENERATING || generation.jobId() == null || (candidate == null) == (failureCode == null)) {
            throw new IllegalStateException("생성 결과를 반영할 수 없는 초안입니다.");
        }
        ProductDraftStatus next = candidate == null ? ProductDraftStatus.FAILED : ProductDraftStatus.READY;
        return new ProductDraft(id, next, requestedByMemberId, facts, images, rejectedByMemberId, rejectedAt, expiresAt, createdAt,
            generation, candidate, failureCode);
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
        return new ProductDraft(id, ProductDraftStatus.REJECTED, requestedByMemberId, facts, images, memberId, now, expiresAt, createdAt,
            generation, candidate, failureCode);
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
