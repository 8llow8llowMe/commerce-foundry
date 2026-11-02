package com.commercefoundry.domainlayer.campaign.application.port.out.query;

import java.util.Set;

public record CampaignConsentQueryResult(Set<Long> agreedMemberIds) {
    public CampaignConsentQueryResult {
        agreedMemberIds = Set.copyOf(agreedMemberIds);
    }
}
