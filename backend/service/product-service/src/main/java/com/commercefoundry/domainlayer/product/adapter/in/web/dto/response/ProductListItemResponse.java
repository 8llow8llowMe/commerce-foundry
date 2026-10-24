package com.commercefoundry.domainlayer.product.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductThumbnailItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "상품 목록 항목")
public record ProductListItemResponse(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "상품명", example = "모코 생활한복 저고리")
    String name,

    @Schema(description = "대표 이미지. 없으면 null", nullable = true)
    ProductThumbnailItem thumbnail,

    @Schema(description = "판매가 — 판매중 옵션 중 최저가. hasOptionSurcharge 가 true 면 '129,000원~' 처럼 표시한다")
    MoneyItem salePrice,

    @Schema(description = "정가 (할인 전). 할인이 없으면(정가 ≤ 표시 판매가 또는 1% 미만 할인) null — listPrice 와 discountRate 는 함께 null 이다", nullable = true)
    MoneyItem listPrice,

    @Schema(description = "정가 대비 할인율(%) — 소수점 아래는 버린다. 할인이 없으면(정가 ≤ 표시 판매가 또는 1% 미만 할인) null — listPrice 와 discountRate 는 함께 null 이다", example = "13", nullable = true)
    Integer discountRate,

    @Schema(description = "판매중 옵션끼리 추가금이 다른가", example = "true")
    boolean hasOptionSurcharge,

    @Schema(description = "판매 상태 — ON_SALE · SOLD_OUT · STOP_SELLING", example = "{\"code\": \"ON_SALE\", \"name\": \"판매중\", \"description\": \"구매할 수 있습니다.\"}")
    CodeNameDescriptionMetadata saleStatus,

    @Schema(description = "맞춤 제작 상품인가. 스토어 맞춤 제작 기능이 꺼져 있으면 false", example = "false")
    boolean customOrder,

    @Schema(description = "프리오더(예약 판매) 상품인가. 스토어 프리오더 기능이 꺼져 있으면 false. 발송 예정일은 상세에서만 내린다", example = "false")
    boolean preorder
) {
}
