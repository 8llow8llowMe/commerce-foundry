package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "운영자 온보딩 상품 등록 사실")
public record ProductRegistrationFactInternalResponse(
    @Schema(description = "스토어에서 구매 가능한 상품이 있는가") boolean storeReady,
    @Schema(description = "운영자가 처음 등록한 미삭제 상품의 등록 시각") Instant memberFirstRegisteredAt
) {
}
