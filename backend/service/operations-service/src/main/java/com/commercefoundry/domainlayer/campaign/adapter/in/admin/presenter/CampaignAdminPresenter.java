package com.commercefoundry.domainlayer.campaign.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.response.CampaignRecipientPreviewResponse;
import com.commercefoundry.domainlayer.campaign.application.info.CampaignRecipientPreviewInfo;
import org.springframework.stereotype.Component;

@Component
public class CampaignAdminPresenter {
    public CampaignRecipientPreviewResponse toResponse(CampaignRecipientPreviewInfo info) {
        var members = info.members().stream()
            .map(member -> new CampaignRecipientPreviewResponse.Member(String.valueOf(member.memberId()), member.marketingAgreed())).toList();
        return new CampaignRecipientPreviewResponse(info.recipientCount(), info.agreedCount(), info.excludedCount(), members);
    }
}
