package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 초안의 AI 생성 회차 (#359 PR-B1). operations 영수증은 (초안, 회차)마다 hash · 요청자 · 만료 시각을 대조해 같은 값이면 재생하고 다르면 409 다 —
 * 그래서 회차를 시작(claim)할 때 정한 값을 여기 고정하고, 같은 회차의 재시도는 다른 어드민이 눌러도 이 값을 그대로 보낸다.
 *
 * <p>회차는 claim 때 하나 오르고, 등록 응답을 반영하면 {@code jobId} 가 붙는다. B1 은 1회차까지만 오른다 — 재생성(다음 회차, 최대 {@link #MAX_GENERATION_NO})은 B3 에서
 * {@link ProductDraft#claimGeneration} 의 허용 상태를 넓혀 연다.
 *
 * @param generationNo 시작한 회차. 0 이면 아직 시작하지 않았다(그때 나머지 칸은 모두 null)
 * @param requestedByMemberId 이 회차를 시작한 어드민
 * @param requestedAt 이 회차를 시작한 시각(마이크로초)
 * @param inputHash 정규 입력 SHA-256 소문자 hex 64자 — 첫 claim 때 정하고 재시도는 저장한 값을 보낸다
 * @param jobId 등록한 operations 작업. 등록 응답을 반영하기 전에는 null
 */
public record ProductDraftGeneration(int generationNo, Long requestedByMemberId, Instant requestedAt, String inputHash, Long jobId) {

    /** 회차 상한 — 첫 생성 + 재생성(#359 D9). operations 영수증 CHECK(1~3)와 같다. */
    public static final int MAX_GENERATION_NO = 3;

    /** 아직 생성을 시작하지 않은 초안. */
    public static final ProductDraftGeneration NONE = new ProductDraftGeneration(0, null, null, null, null);

    public ProductDraftGeneration {
        if (generationNo < 0 || generationNo > MAX_GENERATION_NO) {
            throw new IllegalArgumentException("생성 회차는 0~" + MAX_GENERATION_NO + " 이어야 합니다: " + generationNo);
        }
        if (generationNo == 0) {
            if (requestedByMemberId != null || requestedAt != null || inputHash != null || jobId != null) {
                throw new IllegalArgumentException("시작하지 않은 생성 회차에 기록이 있습니다.");
            }
        } else {
            Objects.requireNonNull(requestedByMemberId, "requestedByMemberId");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(inputHash, "inputHash");
        }
    }

    /** 회차를 시작했는가(claim 됨). */
    public boolean claimed() {
        return generationNo > 0;
    }

    /** 다음 회차 — 요청자 · 시각 · hash 를 고정하고 작업은 비운다. */
    ProductDraftGeneration next(long actorMemberId, Instant claimedAt, String claimedInputHash) {
        return new ProductDraftGeneration(generationNo + 1, actorMemberId, claimedAt, claimedInputHash, null);
    }

    /** 등록한 작업을 붙인 사본. */
    ProductDraftGeneration bound(long boundJobId) {
        return new ProductDraftGeneration(generationNo, requestedByMemberId, requestedAt, inputHash, boundJobId);
    }
}
