package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "서비스 간 SKU 조회 한 건")
public record SkuInternalItem(

    @Schema(description = "SKU 아이디", example = "9004")
    String skuId,

    @Schema(description = "상품 아이디", example = "7301")
    String productId,

    @Schema(description = "상품명", example = "모코 생활한복 저고리")
    String productName,

    @Schema(description = "옵션. 옵션 그룹의 노출 순서 → 아이디 순. 옵션이 없으면 빈 배열")
    List<SkuOptionValueInternalItem> optionValues,

    @Schema(description = "옵션 값 이름을 ' / ' 로 이은 표시명. 옵션이 없으면 빈 문자열", example = "아이보리 / L")
    String optionName,

    @Schema(description = "판매가 = 기본 판매가 + 옵션 추가금")
    MoneyItem salePrice,

    @Schema(description = "옵션 추가금")
    MoneyItem additionalPrice,

    @Schema(description = "판매 상태 — ON_SALE · SOLD_OUT · STOP_SELLING")
    CodeNameDescriptionMetadata saleStatus,

    @Schema(description = "지금 주문할 수 있는가 — 상품이 삭제되지 않았고 노출 중이며 판매 상태가 ON_SALE 일 때만 true. 숨긴 · 삭제된 상품의 SKU 도 행은 내린다", example = "true")
    boolean purchasable,

    @Schema(description = "1회 주문 최대 수량. null 이면 제한 없음", example = "10", nullable = true)
    Integer maxQuantityPerOrder,

    @Schema(description = "맞춤 제작 상품인가. 스토어 맞춤 제작 기능이 꺼져 있으면 false", example = "false")
    boolean customOrder,

    @Schema(
        description = "배송 무게 (그램, 1 이상) — 해외 배송비 견적의 총 무게(Σ 무게 × 수량)에 쓴다. null 이면 미입력이고, 해외 주문이면 호출한 쪽이 거절한다",
        example = "450", nullable = true
    )
    Integer weightGrams,

    @Schema(description = "통관 신고용 HS 코드 (숫자 6~10자리). null 이면 미입력이고, 해외 주문이면 호출한 쪽이 거절한다", example = "6211420000", nullable = true)
    String hsCode
) {
}
