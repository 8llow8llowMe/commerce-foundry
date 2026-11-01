package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item.ProductDraftFactsAdminItem;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item.ProductDraftImageAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 AI 상품 초안. 운영자가 읽는 상태 · 안내만 낸다 — 모델 · 프롬프트 · 토큰 · 비용 · 작업 내부 정보를 내지 않는다 ({@code api-design-guide.md} §2-8).
 * AI 제안(상품명 · 설명 · 검색 태그 · 대체 텍스트 · 확인이 필요한 속성)은 생성 연결 PR 에서 더한다.
 */
@Builder
@Schema(description = "어드민 AI 상품 초안")
public record ProductDraftAdminResponse(

    @Schema(description = "초안 아이디", example = "4611686018427387904")
    String draftId,

    @Schema(description = "초안 상태 {code, name, description} — REQUESTED · GENERATING · READY · FAILED · APPLIED · REJECTED · EXPIRED. "
        + "보관 기간이 지난 진행 중 초안은 EXPIRED 로 보인다")
    CodeNameDescriptionMetadata status,

    @Schema(description = "운영자가 입력한 사실 (입력 그대로)")
    ProductDraftFactsAdminItem facts,

    @Schema(description = "근거 이미지 (배열 순서 = 화면 순서). 없으면 빈 배열")
    List<ProductDraftImageAdminItem> images,

    @Schema(description = "아직 없는 등록 필수 입력 {code, name, description} — PRODUCT_NAME → CATEGORY → THUMBNAIL_IMAGE 순. "
        + "가격 · 옵션 · 재고는 적용 단계에서 입력한다. 없으면 빈 배열")
    List<CodeNameDescriptionMetadata> missingRequiredInputs,

    @Schema(description = "초안을 만든 어드민 회원 아이디", example = "100")
    String requestedByMemberId,

    @Schema(description = "만든 시각 (UTC)", example = "2026-11-01T03:00:00Z")
    Instant requestedAt,

    @Schema(description = "보관 만료 시각 (UTC) — 지나면 적용 · 거절할 수 없다", example = "2026-11-08T03:00:00Z")
    Instant expiresAt,

    @Schema(description = "거절 시각 (UTC). 거절 전에는 null", nullable = true)
    Instant rejectedAt
) {
}
