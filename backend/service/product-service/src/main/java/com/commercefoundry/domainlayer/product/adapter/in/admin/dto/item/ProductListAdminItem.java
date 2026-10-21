package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 상품 목록 한 행. 고객 목록 항목({@code ProductListItemResponse})과 공유하지 않는다 — 가격은 저장된 값 그대로이고, 숨김 · 저장된 판매 상태 · SKU 수를 함께 낸다.
 */
@Builder
@Schema(description = "어드민 상품 목록 항목")
public record ProductListAdminItem(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "상품명", example = "모코 생활한복 저고리")
    String name,

    @Schema(description = "대표 이미지 주소. 없으면 null", example = "https://cdn.example.com/products/4611686018427387904/thumbnail.jpg", nullable = true)
    String thumbnailUrl,

    @Schema(description = "기본 판매가 — 저장된 값 그대로다(옵션 추가금 · 판매중 옵션 최저가를 반영하지 않는다)")
    MoneyItem salePrice,

    @Schema(description = "정가 — 저장된 값 그대로다. 없으면 null", nullable = true)
    MoneyItem listPrice,

    @Schema(
        description = "저장된 판매 상태 — ON_SALE(판매중) · STOP_SELLING(판매 종료)",
        example = "{\"code\": \"ON_SALE\", \"name\": \"판매중\", \"description\": \"판매하도록 설정했습니다. 재고가 없으면 고객 화면에는 품절로 보입니다.\"}"
    )
    CodeNameDescriptionMetadata saleStatus,

    @Schema(description = "스토어프론트 노출 여부. false 면 고객 목록 · 상세에서 빠진다", example = "true")
    boolean visible,

    @Schema(
        description = "재고를 반영한 판매 가능 상태 — ON_SALE · SOLD_OUT · STOP_SELLING. 고객 화면 뱃지와 같은 규칙이다(노출 여부는 보지 않는다)",
        example = "{\"code\": \"SOLD_OUT\", \"name\": \"품절\", \"description\": \"재고가 모두 소진되었습니다.\"}"
    )
    CodeNameDescriptionMetadata availability,

    @Schema(description = "SKU 수 (판매 상태와 무관)", example = "6")
    long skuCount,

    @Schema(description = "상품 정보 수정 시각 (UTC, ISO-8601). SKU · 재고 · 이미지만 바뀐 것은 반영하지 않는다", example = "2026-10-18T09:00:00Z")
    Instant updatedAt
) {
}
