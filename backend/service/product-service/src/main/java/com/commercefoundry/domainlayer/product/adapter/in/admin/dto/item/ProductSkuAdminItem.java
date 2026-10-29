package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.common.dto.item.MoneyItem;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 상품 SKU")
public record ProductSkuAdminItem(

    @Schema(description = "SKU 아이디", example = "4611686018427387904")
    String skuId,

    @Schema(description = "저장된 옵션 값 조합")
    List<String> optionValueIds,

    @Schema(description = "저장된 옵션 추가금")
    MoneyItem additionalPrice,

    @Schema(description = "저장된 판매 상태")
    CodeNameDescriptionMetadata saleStatus,

    @Schema(description = "배송 무게(g). 미입력이면 null", nullable = true)
    Integer weightGrams,

    @Schema(description = "HS 코드. 미입력이면 null", nullable = true)
    String hsCode
) {
}
