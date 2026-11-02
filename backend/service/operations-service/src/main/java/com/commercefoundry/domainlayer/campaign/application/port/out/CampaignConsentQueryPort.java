package com.commercefoundry.domainlayer.campaign.application.port.out;

import com.commercefoundry.domainlayer.campaign.application.port.out.query.CampaignConsentQueryResult;
import java.util.List;

public interface CampaignConsentQueryPort {
    CampaignConsentQueryResult findConsents(List<Long> memberIds);
}
