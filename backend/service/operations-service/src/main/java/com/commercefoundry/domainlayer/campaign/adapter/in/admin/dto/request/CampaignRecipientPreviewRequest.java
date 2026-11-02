package com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.campaign.application.exception.CampaignValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CampaignRecipientPreviewRequest(
    @Schema(description = "중복 포함 1~100개 양수 회원 ID. 첫 등장 순서로 중복 제거", example = "[2, 1, 2]")
    @NotEmpty(message = CampaignValidationMessage.MEMBERS_REQUIRED)
    @Size(max = 100, message = CampaignValidationMessage.MEMBERS_LIMIT)
    List<@NotNull(message = CampaignValidationMessage.MEMBER_POSITIVE) @Positive(message = CampaignValidationMessage.MEMBER_POSITIVE) Long> memberIds
) {
}
