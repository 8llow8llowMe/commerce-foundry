package com.commercefoundry.domainlayer.campaign.application.service.processor;

import com.commercefoundry.domainlayer.campaign.application.info.CampaignRecipientPreviewInfo;
import com.commercefoundry.domainlayer.campaign.application.port.out.CampaignConsentQueryPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CampaignRecipientPreviewProcessor {
    private final CampaignConsentQueryPort consentQueryPort;

    public CampaignRecipientPreviewInfo preview(List<Long> memberIds) {
        List<Long> recipients = memberIds.stream().distinct().toList();
        var consent = consentQueryPort.findConsents(recipients);
        var members = recipients.stream()
            .map(id -> new CampaignRecipientPreviewInfo.Member(id, consent.agreedMemberIds().contains(id))).toList();
        int agreed = (int) members.stream().filter(CampaignRecipientPreviewInfo.Member::marketingAgreed).count();
        return new CampaignRecipientPreviewInfo(members.size(), agreed, members.size() - agreed, members);
    }
}
