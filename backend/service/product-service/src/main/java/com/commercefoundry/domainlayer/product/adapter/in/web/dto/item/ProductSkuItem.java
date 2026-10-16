package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "판매 단위(옵션 조합)")
public record ProductSkuItem(

    @Schema(description = "SKU 아이디", example = "9001")
    String skuId,

    @Schema(description = "옵션 값 아이디. optionGroups 순서로 정렬한다. 옵션이 없는 상품은 빈 배열", example = "[\"5011\", \"5021\"]")
    List<String> optionValueIds,

    @Schema(description = "옵션 추가금 (+5,000원 표기용)")
    MoneyItem additionalPrice,

    @Schema(description = "판매가 = 기본 판매가 + 옵션 추가금 (서버 계산)")
    MoneyItem salePrice,

    @Schema(description = "판매 상태 — ON_SALE · SOLD_OUT · STOP_SELLING", example = "{\"code\": \"ON_SALE\", \"name\": \"판매중\", \"description\": \"구매할 수 있습니다.\"}")
    CodeNameDescriptionMetadata saleStatus
) {
}
