package com.commercefoundry.domainlayer.campaign.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.request.CampaignRecipientPreviewRequest;
import com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.response.CampaignRecipientPreviewResponse;
import com.commercefoundry.domainlayer.campaign.application.port.in.CampaignAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 캠페인", description = "마케팅 수신 동의 미리보기")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/campaigns")
public class CampaignAdminController {
    private final CampaignAdminUseCase campaignAdminUseCase;

    @Operation(summary = "수신자 동의 미리보기",
        description = "최대 100개 ID의 최신 동의를 조회한다. 중복은 첫 순서로 제거한다. 저장·예약·발송하지 않으며 발송 승인이 아니다. "
            + "auth 호출이나 응답 계약 실패는 503 CAMPAIGN_001이다.")
    @AdminAudited(action = "CAMPAIGN_RECIPIENT_PREVIEW")
    @PreAuthorize(AdminAuthority.CS_READ)
    @PostMapping("/recipient-previews")
    public ResponseEntity<Response<CampaignRecipientPreviewResponse>> preview(
        @AuthenticationPrincipal MemberPrincipal member, @Valid @RequestBody CampaignRecipientPreviewRequest request
    ) {
        return ResponseEntity.ok(Response.success(campaignAdminUseCase.preview(member.memberId(), request.memberIds())));
    }
}
