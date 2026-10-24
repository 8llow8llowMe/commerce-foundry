package com.commercefoundry.domainlayer.product.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CategoryPathItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.PreorderItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductImageItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductOptionGroupItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductSkuItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductThumbnailItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "상품 상세 응답")
public record ProductDetailResponse(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "상품명", example = "모코 생활한복 저고리")
    String name,

    @Schema(description = "한 줄 요약 (메타 description 에도 쓴다)", example = "데일리로 입는 면 저고리", nullable = true)
    String summary,

    @Schema(description = "대표 카테고리 경로 (최상위 → 대표 카테고리). 메뉴에서 닿을 수 있는 경로만 — 처음 숨긴 카테고리에서 끊기고 그 아래는 빠진다. 최상위가 숨김이면 빈 목록")
    List<CategoryPathItem> categoryPath,

    @Schema(description = "대표 이미지. 없으면 null", nullable = true)
    ProductThumbnailItem thumbnail,

    @Schema(description = "상세 갤러리 미디어. 이미지와 (스토어 영상 기능이 켜져 있으면) 영상을 노출 순서 → 아이디 오름차순으로 섞는다. 대표 이미지는 들어가지 않는다")
    List<ProductImageItem> images,

    @Schema(
        description = "상세 설명 HTML. 서버가 정제하지 않은 원문이다 — 클라이언트는 허용 목록 방식으로 sanitize 한 뒤 렌더한다"
            + "(script · on* 속성 · javascript: URL · iframe 제거).",
        example = "<p>...</p>", nullable = true
    )
    String descriptionHtml,

    @Schema(description = "판매가 — 판매중 옵션 중 최저가")
    MoneyItem salePrice,

    @Schema(description = "정가 (할인 전). 할인이 없으면(정가 ≤ 표시 판매가 또는 1% 미만 할인) null — listPrice 와 discountRate 는 함께 null 이다", nullable = true)
    MoneyItem listPrice,

    @Schema(description = "정가 대비 할인율(%) — 소수점 아래는 버린다. 할인이 없으면(정가 ≤ 표시 판매가 또는 1% 미만 할인) null — listPrice 와 discountRate 는 함께 null 이다", example = "13", nullable = true)
    Integer discountRate,

    @Schema(description = "판매 상태 — ON_SALE · SOLD_OUT · STOP_SELLING", example = "{\"code\": \"ON_SALE\", \"name\": \"판매중\", \"description\": \"구매할 수 있습니다.\"}")
    CodeNameDescriptionMetadata saleStatus,

    @Schema(description = "1회 주문 최대 수량. null 이면 제한 없음", example = "10", nullable = true)
    Integer maxQuantityPerOrder,

    @Schema(description = "옵션 그룹. 노출 순서 → 아이디 오름차순. 옵션이 없는 상품은 빈 배열")
    List<ProductOptionGroupItem> optionGroups,

    @Schema(description = "판매 단위. 아이디 오름차순. 옵션이 없는 상품은 optionValueIds 가 빈 SKU 1개")
    List<ProductSkuItem> skus,

    @Schema(description = "맞춤 제작 안내. 스토어 맞춤 제작 기능이 꺼져 있거나 맞춤 제작 상품이 아니면 null", nullable = true)
    CustomOrderItem customOrder,

    @Schema(description = "프리오더(예약 판매) 안내. 스토어 프리오더 기능이 꺼져 있거나 프리오더 상품이 아니면 null", nullable = true)
    PreorderItem preorder
) {
}
