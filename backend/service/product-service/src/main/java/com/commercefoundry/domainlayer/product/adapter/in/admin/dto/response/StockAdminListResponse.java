package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 SKU 재고 목록")
public record StockAdminListResponse(

    @Schema(description = "요청 순서(중복 제거 후)의 SKU 재고. 없는 SKU 는 빠진다 — 화면이 누락을 보고 판단한다")
    List<StockAdminResponse> stocks
) {
}
