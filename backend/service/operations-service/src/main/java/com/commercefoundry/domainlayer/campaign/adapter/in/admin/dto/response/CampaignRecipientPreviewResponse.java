package com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "조회 시점의 동의 미리보기. 발송 승인이나 예약이 아니며 발송 직전 재검증이 필요")
public record CampaignRecipientPreviewResponse(
    @Schema(description = "중복 제거한 전체 인원", example = "2")
    int recipientCount,

    @Schema(description = "최신 마케팅 동의 인원", example = "1")
    int agreedCount,

    @Schema(description = "최신 마케팅 미동의 인원", example = "1")
    int excludedCount,

    @Schema(description = "첫 요청 순서의 중복 제거한 동의 상태")
    List<Member> members
) {
    public record Member(
        @Schema(description = "회원 ID", example = "2")
        String memberId,

        @Schema(description = "조회 시점의 최신 마케팅 동의 여부", example = "true")
        boolean marketingAgreed
    ) {
    }
}
