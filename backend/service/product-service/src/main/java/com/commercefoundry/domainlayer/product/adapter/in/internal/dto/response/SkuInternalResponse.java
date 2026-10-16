package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.SkuInternalItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "서비스 간 SKU 벌크 조회 응답")
public record SkuInternalResponse(

    @Schema(description = "요청 순서(중복 제거 후)의 SKU. 없는 아이디는 빠진다 — 호출한 쪽이 누락을 보고 거절한다")
    List<SkuInternalItem> skus
) {
}
