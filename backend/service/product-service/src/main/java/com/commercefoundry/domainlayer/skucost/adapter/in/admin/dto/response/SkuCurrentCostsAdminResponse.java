package com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.response;

import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.item.SkuCostAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "SKU 의 지금 적용되는 원가")
public record SkuCurrentCostsAdminResponse(

    @Schema(description = "SKU 아이디", example = "4611686018427387999")
    String skuId,

    @Schema(description = "유형별 지금 적용되는 원가 — GOODS → PACKAGING 순. 한 번도 입력하지 않은 유형은 항목이 없다 (0원으로 채우지 않는다)")
    List<SkuCostAdminItem> costs
) {
}
