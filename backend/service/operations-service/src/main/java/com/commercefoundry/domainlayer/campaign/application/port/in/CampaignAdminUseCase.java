package com.commercefoundry.domainlayer.campaign.application.port.in;

import com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.response.CampaignRecipientPreviewResponse;
import java.util.List;

public interface CampaignAdminUseCase {
    CampaignRecipientPreviewResponse preview(Long actorMemberId, List<Long> memberIds);
}
