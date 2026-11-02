package com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item.MarketingConsentInternalItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record MarketingConsentInternalResponse(
    @Schema(description = "요청 첫 등장 순서의 회원별 동의 상태, 중복 ID는 한 번만 포함")
    List<MarketingConsentInternalItem> members
) {
}
