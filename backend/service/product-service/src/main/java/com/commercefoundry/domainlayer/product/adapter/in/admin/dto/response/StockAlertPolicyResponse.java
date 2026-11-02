package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "SKU 품절 임박 기준. 미설정은 enabled=false")
public record StockAlertPolicyResponse(

    @Schema(description = "SKU 아이디", example = "4611686018427387904")
    String skuId,

    @Schema(description = "기준 설정 여부")
    boolean enabled,

    @Schema(description = "기준 수량. 미설정이면 null", nullable = true)
    Long thresholdQuantity,

    @Schema(description = "마지막 설정 시각. 미설정이면 null", nullable = true)
    Instant updatedAt
) {
}
