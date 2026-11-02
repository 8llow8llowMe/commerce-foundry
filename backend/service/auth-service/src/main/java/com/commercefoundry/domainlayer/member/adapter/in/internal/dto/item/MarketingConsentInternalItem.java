package com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;

public record MarketingConsentInternalItem(
    @Schema(description = "회원 ID", example = "1")
    String memberId,

    @Schema(description = "현재 고객 계정의 최신 마케팅 동의 여부. 회원/이력이 없거나 철회한 경우 false")
    boolean marketingAgreed
) {
}
