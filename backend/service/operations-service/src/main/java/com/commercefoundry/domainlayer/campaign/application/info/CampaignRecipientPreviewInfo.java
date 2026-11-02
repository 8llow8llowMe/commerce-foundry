package com.commercefoundry.domainlayer.campaign.application.info;

import java.util.List;

public record CampaignRecipientPreviewInfo(int recipientCount, int agreedCount, int excludedCount, List<Member> members) {
    public record Member(Long memberId, boolean marketingAgreed) {
    }
}
