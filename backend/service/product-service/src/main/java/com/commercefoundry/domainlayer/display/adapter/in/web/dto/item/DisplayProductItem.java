package com.commercefoundry.domainlayer.display.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 상품 목록 항목({@code GET /api/v1/products} 의 {@code ProductListItemResponse})과 같은 필드 · 같은 JSON 모양이다. 클래스를 공유하지 않는 것은
 * 같은 서비스의 다른 컨텍스트 DTO 를 쓰지 않는 규칙 때문이다 ({@code architecture-guide.md} §3) — 필드를 바꾸면 두 곳을 함께 바꾼다.
 */
@Builder
@Schema(description = "전시 상품 카드 (상품 목록 항목과 같은 모양)")
public record DisplayProductItem(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "상품명", example = "모코 생활한복 저고리")
    String name,

    @Schema(description = "대표 이미지. 없으면 null", nullable = true)
    DisplayProductThumbnailItem thumbnail,

    @Schema(description = "판매가 — 판매중 옵션 중 최저가. hasOptionSurcharge 가 true 면 '129,000원~' 처럼 표시한다")
    MoneyItem salePrice,

    @Schema(description = "정가 (할인 전). 할인이 없으면(정가 ≤ 표시 판매가 또는 1% 미만 할인) null — listPrice 와 discountRate 는 함께 null 이다", nullable = true)
    MoneyItem listPrice,

    @Schema(description = "정가 대비 할인율(%) — 소수점 아래는 버린다. 할인이 없으면 null — listPrice 와 discountRate 는 함께 null 이다", example = "13", nullable = true)
    Integer discountRate,

    @Schema(description = "판매중 옵션끼리 추가금이 다른가", example = "true")
    boolean hasOptionSurcharge,

    @Schema(description = "판매 상태 — ON_SALE · SOLD_OUT · STOP_SELLING", example = "{\"code\": \"ON_SALE\", \"name\": \"판매중\", \"description\": \"구매할 수 있습니다.\"}")
    CodeNameDescriptionMetadata saleStatus,

    @Schema(description = "맞춤 제작 상품인가. 스토어 맞춤 제작 기능이 꺼져 있으면 false", example = "false")
    boolean customOrder
) {
}
