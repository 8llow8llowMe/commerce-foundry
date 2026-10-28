package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
@Schema(description = "서비스 간 운영자 온보딩 사실 — 국내 배송비 정책 저장")
public record ShippingPolicyFactInternalResponse(

    @Schema(description = "국내 배송비 정책이 있는가 — 누가 저장했든 스토어에 국내 배송비가 준비됐다", example = "true")
    boolean storeReady,

    @Schema(
        description = "그 운영자가 국내 배송비 정책을 처음 저장한 시각 (UTC, ISO-8601). 저장한 적이 없으면 null",
        example = "2026-10-18T03:00:00Z",
        nullable = true
    )
    Instant memberFirstSavedAt
) {
}
