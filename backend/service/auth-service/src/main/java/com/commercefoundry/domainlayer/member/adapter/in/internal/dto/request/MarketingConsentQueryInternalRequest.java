package com.commercefoundry.domainlayer.member.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MarketingConsentQueryInternalRequest(
    @Schema(description = "조회할 회원 ID, 중복을 포함해 최대 100개", example = "[1, 2]")
    @NotEmpty(message = MemberValidationMessage.MARKETING_IDS_REQUIRED)
    @Size(max = 100, message = MemberValidationMessage.MARKETING_IDS_SIZE_MAX)
    List<@NotNull(message = MemberValidationMessage.MARKETING_ID_POSITIVE) @Positive(message = MemberValidationMessage.MARKETING_ID_POSITIVE) Long> memberIds
) {
}
