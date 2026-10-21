package com.commercefoundry.domainlayer.order.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "주문 항목 한 줄. 미리보기는 지금 값, 주문은 주문 시점 스냅숏이다")
public record OrderLineItem(

    @Schema(description = "SKU 아이디", example = "9004")
    String skuId,

    @Schema(description = "상품 아이디", example = "7301")
    String productId,

    @Schema(description = "상품명", example = "모코 생활한복 저고리")
    String productName,

    @Schema(description = "옵션 표시명 (옵션 값을 ' / ' 로 이은 것). 옵션이 없으면 빈 문자열", example = "아이보리 / L")
    String optionName,

    @Schema(description = "단가 — 판매가 (옵션 추가금 포함)")
    MoneyItem unitPrice,

    @Schema(description = "수량", example = "2")
    int quantity,

    @Schema(description = "단가 × 수량")
    MoneyItem lineAmount,

    @Schema(description = "맞춤 제작 상품인가 (주문 시점)", example = "false")
    boolean customOrder,

    @Schema(description = "맞춤 제작 입력. 없으면 빈 배열")
    List<CustomOptionItem> customOptions
) {
}
